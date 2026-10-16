package judozoo.leadercalendar.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 주도주 캘린더 스냅샷 작업 — 휴장일은 휴장으로 남기고, 거래일은 현지 날짜로 잡는다. */
class LeaderCalendarSchedulerTest {
    private val taken = mutableListOf<LocalDate>()
    private val closed = mutableListOf<Pair<Region, LocalDate>>()
    private val service = mockk<LeaderCalendarService> {
        every { snapshotDomestic(any(), any()) } answers { taken += firstArg<LocalDate>(); 0 }
        every { snapshotOverseas(any(), any()) } answers { taken += firstArg<LocalDate>(); 0 }
        every { recordClosed(any(), any(), any()) } answers { closed += firstArg<Region>() to secondArg<LocalDate>() }
    }
    private val calendar = mockk<MarketCalendar>()
    private val registry = SimpleMeterRegistry()

    private fun scheduler(kst: LocalDateTime) = LeaderCalendarScheduler(service, calendar, JobFailures(registry), Clock.fixed(kst.atZone(KST).toInstant(), KST))

    @Nested
    inner class 국내_스냅샷_작업 {
        @Test
        fun `국내 휴장일에는 주도주 대신 휴장으로 남긴다`() {
            every { calendar.isHoliday(any()) } answers { firstArg<Region>() == Region.KR }

            scheduler(LocalDateTime.of(2026, 9, 24, 20, 1)).snapshotDomestic()

            taken.shouldBeEmpty()
            closed shouldBe listOf(Region.KR to LocalDate.of(2026, 9, 24))
        }

        @Test
        fun `실패해도 작업이 죽지 않고 실패로 센다`() {
            every { calendar.isHoliday(any()) } returns false
            every { service.snapshotDomestic(any(), any()) } throws IllegalStateException("키움 오류")

            scheduler(LocalDateTime.of(2026, 9, 23, 20, 1)).snapshotDomestic()

            registry.find("scheduler.job.errors").tag("job_id", "leader-calendar-domestic").counter()!!.count() shouldBe 1.0
        }
    }

    @Nested
    inner class 해외_스냅샷_작업 {
        /** 16:01 뉴욕은 KST로 다음 날 새벽이다 — 한국 날짜를 쓰면 하루 밀린다. */
        @Test
        fun `뉴욕 날짜를 거래일로 잡는다`() {
            every { calendar.isHoliday(any()) } returns false

            scheduler(LocalDateTime.of(2026, 9, 26, 5, 1)).snapshotOverseas()

            taken shouldBe listOf(LocalDate.of(2026, 9, 25))
        }

        @Test
        fun `미국 휴장일에는 주도주 대신 휴장으로 남긴다`() {
            every { calendar.isHoliday(any()) } answers { firstArg<Region>() == Region.US }

            scheduler(LocalDateTime.of(2026, 9, 8, 5, 1)).snapshotOverseas()

            taken.shouldBeEmpty()
            closed shouldBe listOf(Region.US to LocalDate.of(2026, 9, 7))
        }
    }
}
