package judozoo.leadingstock.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LeadingStockSnapshotTest {
    private fun stock(name: String, tradingValue: Long, rate: Double, wideLimitDay: Boolean = false) =
        LeadingStockSnapshot(
            stockCode = name, stockName = name, currentPrice = 1000, priceChangeRate = rate,
            tradingValueRank = 1, accumulatedTradingValue = tradingValue, wideLimitDay = wideLimitDay,
        )

    @Nested
    inner class 제한폭이_넓은_날 {
        @Test
        fun `넘은 적이 있으면 30퍼센트 아래로 내려와도 계속 환산한다`() {
            stock("신규상장주", 900_000_000_000, 26.0, wideLimitDay = true).rateForRanking shouldBe 1.3
        }

        @Test
        fun `상장 첫날은 거래대금을 반으로 견준다`() {
            val listed = stock("신규상장주", 254_600_000_000, 124.62)

            listed.tradingValueForRanking shouldBe 127_300_000_000.0
            listed.accumulatedTradingValue shouldBe 254_600_000_000
        }

        @Test
        fun `넘은 적이 있으면 내려와도 거래대금을 반으로 견준다`() {
            stock("신규상장주", 1_000, 26.0, wideLimitDay = true).tradingValueForRanking shouldBe 500.0
        }

        @Test
        fun `일반 종목의 거래대금은 그대로 견준다`() {
            stock("대장주", 9_000, 1.5).tradingValueForRanking shouldBe 9_000.0
        }

        @Test
        fun `넘은 적이 없는 26퍼센트는 그대로 견준다`() {
            stock("급등주", 1_000_000_000_000, 26.0).rateForRanking shouldBe 26.0
        }

        @Test
        fun `상한가 30퍼센트는 환산하지 않는다`() {
            stock("상한가", 2_000_000_000_000, 30.0).rateForRanking shouldBe 30.0
        }
    }
}
