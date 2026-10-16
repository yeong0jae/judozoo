package judozoo.leadingstock.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import judozoo.leadingstock.domain.InvestorNetBuyState.NetBuyTransition
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InvestorNetBuyStateTest {
    private fun InvestorNetBuyState.feed(net: Long) = advance(net, 10_000, 1_000)

    @Nested
    inner class 순매수_단계 {
        @Test
        fun `단계선에 닿으면 그 단계로 전이된다`() {
            InvestorNetBuyState().feed(10_000).first shouldBe NetBuyTransition(NetTradeSide.BUY, 1)
        }

        @Test
        fun `단계선에 못 미치면 전이가 없다`() {
            InvestorNetBuyState().feed(9_000).first.shouldBeNull()
        }

        @Test
        fun `연이어 다음 단계선을 넘으면 다음 단계로`() {
            InvestorNetBuyState().feed(10_000).second.feed(20_000).first shouldBe NetBuyTransition(NetTradeSide.BUY, 2)
        }

        @Test
        fun `두 단계를 건너뛰어도 도달한 최종 단계만 전이된다`() {
            InvestorNetBuyState().feed(25_000).first shouldBe NetBuyTransition(NetTradeSide.BUY, 2)
        }

        @Test
        fun `완충 안에서 오르내려도 같은 단계가 다시 발화하지 않는다`() {
            val second = InvestorNetBuyState().feed(20_000).second
            val (down, after) = second.feed(19_500)
            val (up, _) = after.feed(20_500)

            (down to up) shouldBe (null to null)
        }

        @Test
        fun `완충을 넘어 떨어지면 한 단계 강등된다`() {
            InvestorNetBuyState().feed(20_000).second.feed(18_900).first shouldBe NetBuyTransition(NetTradeSide.BUY, 1)
        }

        @Test
        fun `중립으로 완전히 복귀하면 전이가 없다`() {
            InvestorNetBuyState().feed(10_000).second.feed(0).first.shouldBeNull()
        }

        @Test
        fun `방향이 뒤집히면 단계를 리셋하고 반대편에서 다시 센다`() {
            val (sell, sellSecond) = InvestorNetBuyState().feed(-20_000)
            sell shouldBe NetBuyTransition(NetTradeSide.SELL, 2)

            sellSecond.feed(12_000).first shouldBe NetBuyTransition(NetTradeSide.BUY, 1)
        }

        @Test
        fun `순매도도 단계선에 닿으면 전이된다`() {
            InvestorNetBuyState().feed(-10_000).first shouldBe NetBuyTransition(NetTradeSide.SELL, 1)
        }
    }
}
