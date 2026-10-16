package judozoo.leadertimeline.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class LeaderTimelineSchedulerTest {
    private var now = LocalDateTime.of(2026, 9, 23, 10, 15, 0, 420_000_000)
    private var holiday = false
    private var failing = false
    private val taken = mutableListOf<Pair<Region, LocalDateTime>>()
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val service = mockk<LeaderTimelineService> {
        every { snapshot(any(), any(), any()) } answers {
            if (failing) throw IllegalStateException("키움 오류")
            taken += firstArg<Region>() to secondArg<LocalDateTime>()
            0
        }
    }
    private val calendar = mockk<MarketCalendar> { every { isHoliday(any()) } answers { holiday } }
    private val registry = SimpleMeterRegistry()
    private val scheduler = LeaderTimelineScheduler(service, calendar, JobFailures(registry), clock)

    @Nested
    inner class 국내_1분_작업 {
        @Test
        fun `그 분을 초 없이 찍는다`() {
            scheduler.snapshotDomestic()

            taken shouldBe listOf(Region.KR to LocalDateTime.of(2026, 9, 23, 10, 15))
        }

        @Test
        fun `마감 분인 20시는 찍고 그 뒤는 찍지 않는다`() {
            now = LocalDateTime.of(2026, 9, 23, 20, 0)
            scheduler.snapshotDomestic()
            now = LocalDateTime.of(2026, 9, 23, 20, 1)
            scheduler.snapshotDomestic()

            taken shouldBe listOf(Region.KR to LocalDateTime.of(2026, 9, 23, 20, 0))
        }

        @Test
        fun `장 사이 쉬는 구간은 찍지 않는다`() {
            now = LocalDateTime.of(2026, 9, 23, 15, 33)

            scheduler.snapshotDomestic()

            taken.shouldBeEmpty()
        }

        @Test
        fun `휴장일은 찍지 않는다`() {
            holiday = true

            scheduler.snapshotDomestic()

            taken.shouldBeEmpty()
        }

        @Test
        fun `실패해도 작업이 죽지 않고 실패로 센다`() {
            failing = true

            scheduler.snapshotDomestic()

            registry.find("scheduler.job.errors").tag("job_id", "leader-timeline-domestic").counter()!!.count() shouldBe 1.0
        }
    }

    @Nested
    inner class 해외_1분_작업 {
        @Test
        fun `뉴욕 시각으로 찍는다`() {
            now = LocalDateTime.of(2026, 9, 24, 0, 30)

            scheduler.snapshotOverseas()

            taken shouldBe listOf(Region.US to LocalDateTime.of(2026, 9, 23, 11, 30))
        }

        @Test
        fun `서머타임이 끝나면 한 시간 더 뒤로 간다`() {
            now = LocalDateTime.of(2026, 11, 10, 0, 30)

            scheduler.snapshotOverseas()

            taken shouldBe listOf(Region.US to LocalDateTime.of(2026, 11, 9, 10, 30))
        }
    }
}
