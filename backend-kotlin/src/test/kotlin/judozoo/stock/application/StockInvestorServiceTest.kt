package judozoo.stock.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.platform.kiwoom.InvestorTrendDay
import judozoo.platform.kiwoom.KiwoomInvestor
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StockInvestorServiceTest {
    private val kiwoom = mockk<KiwoomInvestor>()
    private val service = StockInvestorService(kiwoom)

    private val day = InvestorTrendDay(
        date = "2026-09-11", individualNet = 1200, foreignNet = -800, institutionNet = -400,
        financialInvestmentNet = 100, insuranceNet = -50, trustNet = 200, privateEquityNet = -20, pensionFundNet = 150, bankNet = -10,
    )

    @Nested
    inner class 종목_투자자_수급 {
        /** 억으로 반올림하면 기관 세부 같은 작은 수급이 0으로 뭉개진다. */
        @Test
        fun `백만원 단위로 그대로 돌려준다`() {
            every { kiwoom.fetchInvestorTrend("005930") } returns listOf(day)

            val result = service.investorDailyHistory("005930", 5).single()

            result.date shouldBe LocalDate.of(2026, 9, 11)
            result.individualMillion shouldBe 1200
            result.breakdown.pensionFundMillion shouldBe 150
        }

        @Test
        fun `날짜 형식이 어긋난 행은 버린다`() {
            every { kiwoom.fetchInvestorTrend("005930") } returns listOf(day.copy(date = ""))

            service.investorDailyHistory("005930", 5).shouldBeEmpty()
        }

        @Test
        fun `요청한 거래일 수만큼만 준다`() {
            every { kiwoom.fetchInvestorTrend("005930") } returns List(5) { day }

            service.investorDailyHistory("005930", 3).size shouldBe 3
        }

        @Test
        fun `같은 종목 같은 개수는 1분 동안 다시 부르지 않는다`() {
            every { kiwoom.fetchInvestorTrend("005930") } returns listOf(day)

            service.investorDailyHistory("005930", 5)
            service.investorDailyHistory("005930", 5)

            verify(exactly = 1) { kiwoom.fetchInvestorTrend("005930") }
        }

        /** 키움이 실패하면 빈 목록이 온다 — 그걸 들고 있으면 1분 동안 화면이 비어 있다. */
        @Test
        fun `빈 결과는 들고 있지 않는다`() {
            every { kiwoom.fetchInvestorTrend("005930") } returns emptyList()

            service.investorDailyHistory("005930", 5)
            service.investorDailyHistory("005930", 5)

            verify(exactly = 2) { kiwoom.fetchInvestorTrend("005930") }
        }
    }
}
