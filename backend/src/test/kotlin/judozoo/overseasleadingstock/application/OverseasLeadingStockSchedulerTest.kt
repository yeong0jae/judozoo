package judozoo.overseasleadingstock.application

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import judozoo.library.JobFailures
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 해외 폴러 — 후보 풀 갱신은 미국 장중에만, 분봉 마감 확정은 미국 개장일에만 돈다. */
class OverseasLeadingStockSchedulerTest {
    private val service = mockk<OverseasLeadingStockService>()
    private val calendar = mockk<MarketCalendar>()
    private val registry = SimpleMeterRegistry()
    private val scheduler = OverseasLeadingStockScheduler(service, calendar, JobFailures(registry))

    private fun failures(job: String) = registry.find("scheduler.job.errors").tag("job_id", job).counter()?.count() ?: 0.0

    @Nested
    inner class 해외_후보_풀_갱신 {
        private fun us(holiday: Boolean, tradingHours: Boolean) {
            every { calendar.usMarketStatus() } returns (holiday to tradingHours)
            every { service.refreshTradingValuePool() } just runs
        }

        @Test
        fun `미국 장중이면 후보 풀을 갈아 끼운다`() {
            us(holiday = false, tradingHours = true)
            scheduler.refreshTradingValuePool()
            verify(exactly = 1) { service.refreshTradingValuePool() }
        }

        @Test
        fun `미국 휴장이면 갱신하지 않는다`() {
            us(holiday = true, tradingHours = true)
            scheduler.refreshTradingValuePool()
            verify(exactly = 0) { service.refreshTradingValuePool() }
        }

        @Test
        fun `미국 장 시간이 아니면 갱신하지 않는다`() {
            us(holiday = false, tradingHours = false)
            scheduler.refreshTradingValuePool()
            verify(exactly = 0) { service.refreshTradingValuePool() }
        }

        @Test
        fun `실패해도 폴러가 죽지 않고 실패로 센다`() {
            every { calendar.usMarketStatus() } returns (false to true)
            every { service.refreshTradingValuePool() } throws IllegalStateException("KIS 오류")

            scheduler.refreshTradingValuePool()

            failures("overseas-trading-value-pool-refresher") shouldBe 1.0
        }
    }

    @Nested
    inner class 해외_분봉_마감_확정 {
        @Test
        fun `미국 개장일이면 오늘 봉을 굳혀 넘긴다`() {
            every { calendar.isHoliday(Region.US) } returns false
            every { service.settleMinutes() } returns 0

            scheduler.settleMinutes()

            verify(exactly = 1) { service.settleMinutes() }
        }

        @Test
        fun `일부 종목이 실패하면 실패로 센다`() {
            every { calendar.isHoliday(Region.US) } returns false
            every { service.settleMinutes() } returns 2

            scheduler.settleMinutes()

            failures("overseas-minute-settler") shouldBe 1.0
        }

        @Test
        fun `미국 휴장이면 하지 않는다`() {
            every { calendar.isHoliday(Region.US) } returns true

            scheduler.settleMinutes()

            verify(exactly = 0) { service.settleMinutes() }
        }
    }
}
