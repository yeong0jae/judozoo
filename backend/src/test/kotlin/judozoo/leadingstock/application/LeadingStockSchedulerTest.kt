package judozoo.leadingstock.application

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.MarketStatus
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class LeadingStockSchedulerTest {
    private var now = LocalDateTime.of(2026, 9, 11, 10, 0)
    private var holiday = false
    private var tradingHours = true
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val calendar = mockk<MarketCalendar> {
        every { marketStatus() } answers { MarketStatus(holiday, tradingHours) }
        every { isHoliday(Region.KR) } answers { holiday }
    }
    private val service = mockk<LeadingStockService> {
        every { refreshTradingValuePool() } just runs
        every { syncTodayMinutes() } returns 0
        every { settleTodayMinutes() } returns 0
    }
    private val stockSignals = mockk<StockSignalDetector> { every { detect() } just runs }
    private val marketFlows = mockk<MarketFlowDetector> { every { detect() } just runs }
    private val indexRebound = mockk<IndexReboundDetector> { every { detect() } just runs }
    private val registry = SimpleMeterRegistry()
    private val scheduler = LeadingStockScheduler(service, stockSignals, marketFlows, indexRebound, calendar, JobFailures(registry), clock)

    private fun failures(job: String) = registry.find("scheduler.job.errors").tag("job_id", job).counter()?.count() ?: 0.0

    private fun runAll() {
        scheduler.refreshTradingValuePool()
        scheduler.syncTodayMinutes()
        scheduler.detectStockSignals()
        scheduler.detectMarketFlows()
        scheduler.detectIndexRebound()
    }

    @Nested
    inner class 장중 {
        @Test
        fun `정규장이면 모든 폴러가 돈다`() {
            runAll()

            verify(exactly = 1) { service.refreshTradingValuePool() }
            verify(exactly = 1) { service.syncTodayMinutes() }
            verify(exactly = 1) { stockSignals.detect() }
            verify(exactly = 1) { marketFlows.detect() }
            verify(exactly = 1) { indexRebound.detect() }
        }

        @Test
        fun `실패해도 폴러가 죽지 않고 잡마다 실패로 센다`() {
            every { service.refreshTradingValuePool() } throws IllegalStateException("키움 오류")
            every { service.syncTodayMinutes() } throws IllegalStateException("후보 조회 실패")
            every { stockSignals.detect() } throws IllegalStateException("DB 오류")
            every { marketFlows.detect() } throws IllegalStateException("키움 오류")
            every { indexRebound.detect() } throws IllegalStateException("키움 오류")

            runAll()

            listOf("trading-value-pool-refresher", "today-minute-syncer", "signal-event-poller", "market-flow-poller", "index-rebound-poller")
                .map(::failures) shouldBe List(5) { 1.0 }
        }

        @Test
        fun `분봉을 일부 종목만 못 받아도 회차당 한 번 실패로 센다`() {
            every { service.syncTodayMinutes() } returns 2

            scheduler.syncTodayMinutes()

            failures("today-minute-syncer") shouldBe 1.0
        }
    }

    @Nested
    inner class 쉬는_때 {
        @Test
        fun `휴장이면 아무것도 돌지 않는다`() {
            holiday = true

            runAll()

            verify(exactly = 0) { service.refreshTradingValuePool() }
            verify(exactly = 0) { service.syncTodayMinutes() }
            verify(exactly = 0) { stockSignals.detect() }
            verify(exactly = 0) { marketFlows.detect() }
            verify(exactly = 0) { indexRebound.detect() }
        }

        @Test
        fun `장 시간이 아니면 후보 풀·분봉·종목 시그널이 돌지 않는다`() {
            tradingHours = false
            now = LocalDateTime.of(2026, 9, 11, 21, 0)

            runAll()

            verify(exactly = 0) { service.refreshTradingValuePool() }
            verify(exactly = 0) { service.syncTodayMinutes() }
            verify(exactly = 0) { stockSignals.detect() }
            verify(exactly = 0) { marketFlows.detect() }
        }

        @Test
        fun `시장 수급은 프리마켓에도 돌지만 지수 반등은 정규장만 돈다`() {
            now = LocalDateTime.of(2026, 9, 11, 8, 30)

            runAll()

            verify(exactly = 1) { marketFlows.detect() }
            verify(exactly = 0) { indexRebound.detect() }
        }

        @Test
        fun `정규장 마감 15시 30분까지는 지수 반등이 돈다`() {
            now = LocalDateTime.of(2026, 9, 11, 15, 30)
            scheduler.detectIndexRebound()
            now = LocalDateTime.of(2026, 9, 11, 15, 31)
            scheduler.detectIndexRebound()

            verify(exactly = 1) { indexRebound.detect() }
        }
    }

    @Nested
    inner class 당일_분봉_마감_확정 {
        @Test
        fun `평일이면 확정한다`() {
            tradingHours = false
            now = LocalDateTime.of(2026, 9, 11, 20, 1)

            scheduler.settleTodayMinutes()

            verify(exactly = 1) { service.settleTodayMinutes() }
        }

        @Test
        fun `휴장이면 하지 않는다`() {
            holiday = true

            scheduler.settleTodayMinutes()

            verify(exactly = 0) { service.settleTodayMinutes() }
        }

        @Test
        fun `일부 종목이 실패하면 실패로 센다`() {
            every { service.settleTodayMinutes() } returns 2

            scheduler.settleTodayMinutes()

            failures("today-minute-settler") shouldBe 1.0
        }
    }
}
