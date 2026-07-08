package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InvestorFlowStateTest : FunSpec({

    val reversal = 2_000L // 0.2조

    // 누적(억원, 부호 포함)을 순서대로 흘려 마지막 전환을 본다.
    fun runFlow(vararg nets: Long): FlowTurn? {
        var state = InvestorFlowState.INITIAL
        var last: FlowTurn? = null
        nets.forEach { net ->
            val (turn, next) = state.advance(net, reversal)
            last = turn
            state = next
        }
        return last
    }

    context("순매수 흐름 전환") {
        test("정점에서 임계 이상 되돌리면 전환으로 본다") {
            // 외인 순매도 정점 -6.8조 → -6.5조(0.3조 되돌림 ≥ 0.2조) = 매수 전환. 정점 -6.8조를 실어 보낸다.
            runFlow(-10_000, -68_000, -65_000) shouldBe FlowTurn(NetTradeSide.BUY, -68_000)
        }

        test("임계 미만 되돌림은 전환이 아니다") {
            // -6.8조 → -6.7조 (0.1조 < 0.2조)
            runFlow(-68_000, -67_000) shouldBe null
        }

        test("진행 방향으로 정점을 갱신하는 동안은 전환이 없다") {
            runFlow(-30_000, -50_000, -68_000) shouldBe null
        }

        test("전환 후 반대 방향에서 다시 임계만큼 되돌리면 재전환을 잡는다") {
            // 매도 정점 -6.8조 → -6.5조(매수 전환) → -6.7조(0.2조 되돌림) = 매도 전환. 재전환 정점은 -6.5조.
            runFlow(-68_000, -65_000, -67_000) shouldBe FlowTurn(NetTradeSide.SELL, -65_000)
        }

        test("순매수 방향도 대칭으로 동작한다") {
            // 순매수 정점 +5조 → +4.7조(0.3조 되돌림) = 매도 전환. 정점 +5조를 실어 보낸다.
            runFlow(20_000, 50_000, 47_000) shouldBe FlowTurn(NetTradeSide.SELL, 50_000)
        }
    }
})
