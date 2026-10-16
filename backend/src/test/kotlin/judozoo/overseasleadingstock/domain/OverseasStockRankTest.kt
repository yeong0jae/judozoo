package judozoo.overseasleadingstock.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

class OverseasStockRankTest {
    private fun stock(symbol: String = "AAA", ename: String = "AAA CORP", price: Double = 100.0, diff: Double = 5.0, rate: Double = 5.26, tradingValue: Double = 1e9) =
        OverseasStockRank(1, "NAS", symbol, symbol, ename, price, diff, rate, tradingValue)

    @Nested
    inner class ETF_판별 {
        @ParameterizedTest
        @ValueSource(strings = ["SPDR S&P 500 ETF TRUST", "ISHARES CORE", "INVESCO QQQ", "VANGUARD TOTAL", "PROSHARES ULTRA", "DIREXION DAILY", "GLOBAL X FUNDS", "SOME ETN"])
        fun `발행사 브랜드가 보이면 ETF로 본다`(ename: String) {
            stock(ename = ename).isEtf shouldBe true
        }

        @ParameterizedTest
        @ValueSource(strings = ["APPLE INC", "JPMORGAN CHASE & CO", "TRUST BANK CORP"])
        fun `일반기업은 통과시킨다`(ename: String) {
            stock(ename = ename).isEtf shouldBe false
        }
    }

    @Nested
    inner class 전일_종가 {
        @Test
        fun `현재가에서 대비를 빼면 전일 종가다`() {
            stock(price = 100.0, diff = 5.0).previousClose shouldBe 95.0
        }

        @Test
        fun `내린 날은 대비가 음수라 전일 종가가 더 높다`() {
            stock(price = 100.0, diff = -4.0).previousClose shouldBe 104.0
        }
    }

    @Nested
    inner class 정규장_시가 {
        @Test
        fun `장전 거래는 건너뛰고 09시30분 첫 봉의 시가를 쓴다`() {
            val bars = listOf(
                LocalDateTime.of(2026, 10, 1, 9, 29) to 99.0,
                LocalDateTime.of(2026, 10, 1, 9, 31) to 102.0,
                LocalDateTime.of(2026, 10, 1, 9, 30) to 101.0,
            )

            UsRegularSession.openPrice(bars) shouldBe 101.0
        }

        @Test
        fun `아직 장전이면 시가가 없다`() {
            UsRegularSession.openPrice(listOf(LocalDateTime.of(2026, 10, 1, 8, 0) to 99.0)).shouldBeNull()
        }

        @Test
        fun `분봉이 없으면 시가가 없다`() {
            UsRegularSession.openPrice(emptyList()).shouldBeNull()
        }
    }

    @Nested
    inner class 해외_주도주_선정 {
        @Test
        fun `거래대금 꼴찌는 아무리 올라도 맨 위로 오지 못한다`() {
            val pool = listOf(stock("BIG", rate = 2.0, tradingValue = 9e9), stock("BOTH", rate = 9.0, tradingValue = 5e9), stock("HOT", rate = 40.0, tradingValue = 1e8))

            OverseasStockRanks(pool).leaders(3).last().symbol shouldBe "HOT"
        }

        @Test
        fun `내린 종목은 빠진다`() {
            val pool = listOf(stock("DOWN", rate = -1.0, tradingValue = 9e9), stock("UP", rate = 1.0, tradingValue = 1e8))

            OverseasStockRanks(pool).leaders(5).map { it.symbol } shouldBe listOf("UP")
        }
    }
}
