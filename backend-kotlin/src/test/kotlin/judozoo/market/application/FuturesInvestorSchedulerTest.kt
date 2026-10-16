package judozoo.market.application

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import judozoo.library.KST
import judozoo.platform.kis.KisFutures
import judozoo.stock.domain.Market
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDateTime

/** 선물 투자자 폴러 — 선물 장중에만 1분마다 당일 누적을 찍는다. 돌지 않은 과거는 소급할 수 없다. */
class FuturesInvestorSchedulerTest {
    private val net = KisFutures.FuturesInvestors(100, -50, -50, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    private val futures = mockk<KisFutures> { every { fetchInvestors(any()) } returns net }
    private val investors = mockk<MarketInvestors> { every { recordFutures(any(), any(), any(), any()) } just runs }
    private var holiday = false
    private val calendar = mockk<MarketCalendar> { every { isHoliday(any()) } answers { holiday } }

    private fun poll(at: LocalDateTime) = FuturesInvestorScheduler(futures, investors, calendar, Clock.fixed(at.atZone(KST).toInstant(), KST)).poll()

    @Nested
    inner class 선물_투자자_스냅샷 {
        @Test
        fun `장중이면 두 시장을 그 시각으로 남긴다`() {
            val at = LocalDateTime.of(2026, 9, 11, 10, 0)

            poll(at)

            verify { investors.recordFutures(Market.KOSPI, at.toLocalDate(), at, net) }
            verify { investors.recordFutures(Market.KOSDAQ, at.toLocalDate(), at, net) }
        }

        @Test
        fun `선물 개장 전과 마감 뒤에는 찍지 않는다`() {
            poll(LocalDateTime.of(2026, 9, 11, 8, 44))
            poll(LocalDateTime.of(2026, 9, 11, 15, 46))

            verify(exactly = 0) { investors.recordFutures(any(), any(), any(), any()) }
        }

        @Test
        fun `휴장일에는 찍지 않는다`() {
            holiday = true

            poll(LocalDateTime.of(2026, 9, 11, 10, 0))

            verify(exactly = 0) { futures.fetchInvestors(any()) }
        }

        @Test
        fun `한 시장을 못 받아도 다른 시장은 남긴다`() {
            every { futures.fetchInvestors(Market.KOSPI) } returns null

            poll(LocalDateTime.of(2026, 9, 11, 10, 0))

            verify(exactly = 1) { investors.recordFutures(Market.KOSDAQ, any(), any(), any()) }
            verify(exactly = 0) { investors.recordFutures(Market.KOSPI, any(), any(), any()) }
        }
    }
}
