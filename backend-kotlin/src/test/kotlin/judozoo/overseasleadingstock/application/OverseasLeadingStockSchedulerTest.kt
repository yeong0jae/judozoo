package judozoo.overseasleadingstock.application

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 해외 폴러 — 후보 풀 갱신은 미국 장중에만, 분봉 마감 확정은 미국 개장일에만 돈다. */
class OverseasLeadingStockSchedulerTest {
    private val service = mockk<OverseasLeadingStockService>()
    private val calendar = mockk<MarketCalendar>()
    private val scheduler = OverseasLeadingStockScheduler(service, calendar)

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
        fun `실패해도 폴러가 죽지 않는다`() {
            every { calendar.usMarketStatus() } returns (false to true)
            every { service.refreshTradingValuePool() } throws IllegalStateException("KIS 오류")

            scheduler.refreshTradingValuePool()
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
        fun `미국 휴장이면 하지 않는다`() {
            every { calendar.isHoliday(Region.US) } returns true

            scheduler.settleMinutes()

            verify(exactly = 0) { service.settleMinutes() }
        }
    }
}
