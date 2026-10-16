package judozoo.leadingstock.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import judozoo.leadingstock.domain.InvestorFlowState.FlowTurn
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InvestorFlowStateTest {
    private fun flow(vararg nets: Long): FlowTurn? {
        var state = InvestorFlowState()
        var last: FlowTurn? = null
        nets.forEach { state.advance(it, 2_000).let { (turn, next) -> last = turn; state = next } }
        return last
    }

    @Nested
    inner class 순매수_흐름_전환 {
        @Test
        fun `정점에서 임계 이상 되돌리면 전환으로 본다`() {
            flow(-10_000, -68_000, -65_000) shouldBe FlowTurn(NetTradeSide.BUY, -68_000)
        }

        @Test
        fun `임계 미만 되돌림은 전환이 아니다`() {
            flow(-68_000, -67_000).shouldBeNull()
        }

        @Test
        fun `진행 방향으로 정점을 갱신하는 동안은 전환이 없다`() {
            flow(-30_000, -50_000, -68_000).shouldBeNull()
        }

        @Test
        fun `전환 후 반대 방향에서 다시 되돌리면 재전환을 잡는다`() {
            flow(-68_000, -65_000, -67_000) shouldBe FlowTurn(NetTradeSide.SELL, -65_000)
        }

        @Test
        fun `순매수 방향도 대칭으로 동작한다`() {
            flow(20_000, 50_000, 47_000) shouldBe FlowTurn(NetTradeSide.SELL, 50_000)
        }
    }
}
