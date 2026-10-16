package judozoo.leadingstock.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LeadingStocksTest {
    private fun stock(name: String, tradingValue: Long, rate: Double, limitUp: Boolean = false, wideLimitDay: Boolean = false) =
        LeadingStockSnapshot(
            stockCode = name, stockName = name, currentPrice = 1000, priceChangeRate = rate, tradingValueRank = 1,
            accumulatedTradingValue = tradingValue, limitUp = limitUp, wideLimitDay = wideLimitDay,
        )

    private fun List<LeadingStockSnapshot>.names() = map { it.stockName }

    @Nested
    inner class 주도주_선정 {
        @Test
        fun `상한가 잡주가 두 축 모두 상위인 종목을 이기지 못한다`() {
            val pool = listOf(stock("대장주", 7_000_000_000_000, 4.0), stock("둘다상위", 900_000_000_000, 15.0), stock("상한가잡주", 100_000_000_000, 30.0))

            LeadingStocks(pool).leaders(3).names().last() shouldBe "상한가잡주"
        }

        @Test
        fun `거래대금이 크면 덜 올라도 앞선다`() {
            val pool = listOf(stock("대장주", 9_000_000_000_000, 1.0), stock("잘오른중형주", 1_000_000_000_000, 9.0))

            LeadingStocks(pool).leaders(2).names() shouldBe listOf("대장주", "잘오른중형주")
        }

        @Test
        fun `등락률이 바닥이면 거래대금 1위도 밀린다`() {
            val pool = (1..8).map { stock("중위권$it", (10L - it) * 100_000_000_000, 2.0 + it) } +
                listOf(stock("거래대금1위", 9_000_000_000_000, 0.1), stock("둘다상위", 1_000_000_000_000, 20.0))

            LeadingStocks(pool).leaders(1).names() shouldBe listOf("둘다상위")
        }

        @Test
        fun `내린 종목은 거래대금이 아무리 커도 빠진다`() {
            val pool = listOf(stock("돈만몰린하락주", 9_000_000_000_000, -3.0), stock("보합", 8_000_000_000_000, 0.0), stock("오른종목", 100_000_000_000, 2.0))

            LeadingStocks(pool).leaders(5).names() shouldBe listOf("오른종목")
        }

        @Test
        fun `오른 종목이 없는 날은 빈 목록이다`() {
            LeadingStocks(listOf(stock("하락주", 9_000_000_000_000, -3.0))).leaders(5).shouldBeEmpty()
        }
    }

    @Nested
    inner class 상한가_추리기 {
        @Test
        fun `후보 풀 전체에서 고른다`() {
            val pool = listOf(stock("대장주", 9_000_000_000_000, 3.0), stock("잠긴잡주", 20_000_000_000, 29.9, limitUp = true))

            LeadingStocks(pool).limitUps().names() shouldBe listOf("잠긴잡주")
            LeadingStocks(pool).leaders(1).names() shouldNotContain "잠긴잡주"
        }

        @Test
        fun `많이 올랐어도 상한가가 아니면 빠진다`() {
            val pool = listOf(stock("신규상장주", 300_000_000_000, 153.0), stock("진짜상한가", 20_000_000_000, 29.94, limitUp = true))

            LeadingStocks(pool).limitUps().names() shouldBe listOf("진짜상한가")
        }

        @Test
        fun `받은 순서를 그대로 지킨다`() {
            val pool = listOf(
                stock("먼저", 90_000_000_000, 29.9, limitUp = true), stock("중간", 50_000_000_000, 3.0), stock("나중", 80_000_000_000, 30.0, limitUp = true),
            )

            LeadingStocks(pool).limitUps().names() shouldBe listOf("먼저", "나중")
        }

        @Test
        fun `한 종목도 없으면 빈 목록이다`() {
            LeadingStocks(listOf(stock("평범주", 9_000_000_000_000, 3.0))).limitUps().shouldBeEmpty()
        }
    }

    @Nested
    inner class 제한폭이_넓은_날 {
        @Test
        fun `신규상장주가 공모가 대비 등락률로 1위를 차지하지 않는다`() {
            val pool = listOf(stock("대장주", 9_000_000_000_000, 1.5), stock("신규상장주", 2_000_000_000_000, 86.9), stock("잘오른주", 900_000_000_000, 12.0))

            LeadingStocks(pool).leaders(3).names() shouldBe listOf("대장주", "신규상장주", "잘오른주")
        }

        @Test
        fun `제한폭 가까이 오른 신규상장주는 여전히 앞선다`() {
            val pool = listOf(stock("대장주", 9_000_000_000_000, 1.5), stock("신규상장주", 2_000_000_000_000, 250.0), stock("잘오른주", 900_000_000_000, 12.0))

            LeadingStocks(pool).leaders(1).names() shouldBe listOf("신규상장주")
        }

        @Test
        fun `넘은 적이 있으면 30퍼센트 아래로 내려와도 계속 환산한다`() {
            val pool = listOf(
                stock("대장주", 9_000_000_000_000, 1.5), stock("신규상장주", 900_000_000_000, 26.0, wideLimitDay = true), stock("잘오른주", 1_000_000_000_000, 12.0),
            )

            LeadingStocks(pool).leaders(3).names().last() shouldBe "신규상장주"
        }

        @Test
        fun `신규상장주가 거래대금만으로 1위를 차지하지 않는다`() {
            val pool = listOf(
                stock("브릴스", 254_600_000_000, 124.62), stock("두산에너빌리티", 253_900_000_000, 1.23), stock("현대모비스", 203_200_000_000, 4.90),
                stock("덕산넵코어스", 179_900_000_000, 2.95), stock("한미반도체", 160_600_000_000, 1.56), stock("세미파이브", 117_700_000_000, 16.86),
            )

            LeadingStocks(pool).leaders(1).names() shouldNotBe listOf("브릴스")
        }

        @Test
        fun `보이는 등락률은 그대로다`() {
            LeadingStocks(listOf(stock("신규상장주", 1_000_000_000_000, 86.9))).leaders(1)[0].priceChangeRate shouldBe 86.9
        }

        @Test
        fun `상한가 30퍼센트는 환산하지 않는다`() {
            val pool = listOf(
                stock("대장주", 3_000_000_000_000, 1.0), stock("상한가", 2_000_000_000_000, 30.0, limitUp = true), stock("조금오른주", 1_000_000_000_000, 5.0),
            )

            LeadingStocks(pool).leaders(1).names() shouldBe listOf("상한가")
        }
    }
}
