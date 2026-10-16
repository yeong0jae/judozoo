package judozoo.closingbet.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import judozoo.closingbet.domain.PhaseTest.Companion.FRI
import judozoo.leadingstock.domain.MinuteCandle
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SellWindowTest {
    private fun bar(h: Int, m: Int, high: Long, low: Long, volume: Long = 10) = MinuteCandle(FRI.atTime(h, m), low, high, low, high, volume, high * volume)

    @Nested
    inner class 아침_5분_매도 {
        @Test
        fun `NXT 종목은 08시부터 5분 고가 저가 중간`() {
            val got = SellWindow.price(listOf(bar(7, 59, 999, 1), bar(8, 0, 110, 100), bar(8, 4, 120, 105), bar(8, 5, 999, 1)), FRI, true, 100)

            (got.price to got.windowStart) shouldBe (110.0 to FRI.atTime(8, 0))
        }

        @Test
        fun `NXT 비상장 종목은 09시부터`() {
            SellWindow.price(listOf(bar(8, 0, 999, 1), bar(9, 0, 201, 200)), FRI, false, 100).price shouldBe 200.5
        }

        @Test
        fun `창에 체결이 없으면 첫 체결부터 5분으로 미룬다`() {
            val got = SellWindow.price(listOf(bar(9, 12, 130, 120), bar(9, 16, 150, 110), bar(9, 17, 999, 1)), FRI, false, 100)

            (got.price to got.windowStart) shouldBe (130.0 to FRI.atTime(9, 12))
        }

        @Test
        fun `거래량 없는 봉은 체결로 치지 않는다`() {
            SellWindow.price(listOf(bar(9, 0, 999, 1, volume = 0), bar(9, 6, 102, 100)), FRI, false, 100).windowStart shouldBe FRI.atTime(9, 6)
        }

        @Test
        fun `그날 끝까지 체결이 없으면 매수가로 0퍼센트`() {
            val got = SellWindow.price(emptyList(), FRI, true, 184_500)

            (got.price to got.windowStart) shouldBe (184_500.0 to null)
            Outcome.of(108, 184_500, got.price) shouldBe Outcome(0, 0.0)
        }

        @Test
        fun `수익은 주식 수와 가격 차이로 낸다`() {
            val o = Outcome.of(108, 184_500, 188_375.5)

            o.pnl shouldBe 418_554
            o.rate shouldBe (2.1005 plusOrMinus 1e-4)
        }

        /** Python `round`와 같게 짝수 쪽으로 — 0.5원 단위 매도가에 홀수 주식 수면 .5가 나온다. */
        @Test
        fun `수익금 반올림은 짝수 쪽으로 한다`() {
            Outcome.of(1, 100, 100.5).pnl shouldBe 0
            Outcome.of(3, 100, 100.5).pnl shouldBe 2
        }

        @Test
        fun `결과는 매도 창이 끝나면 공개한다`() {
            SellWindow.revealAt(FRI, true) shouldBe FRI.atTime(8, 5)
            SellWindow.revealAt(FRI, false) shouldBe FRI.atTime(9, 5)
        }
    }
}
