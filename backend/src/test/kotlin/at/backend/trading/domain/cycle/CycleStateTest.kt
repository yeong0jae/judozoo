package at.backend.trading.domain.cycle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CycleStateTest : FunSpec({

    context("Initiated 상태 전이") {
        val initiated = CycleState.Initiated

        test("Buying(1)으로 전이 허용") {
            initiated.canTransitionTo(CycleState.Buying(1)) shouldBe true
        }

        test("Buying(2)로 직접 전이 불허 — 1차부터 시작해야 함") {
            initiated.canTransitionTo(CycleState.Buying(2)) shouldBe false
        }

        test("Holding으로 직접 전이 불허") {
            initiated.canTransitionTo(CycleState.Holding) shouldBe false
        }

        test("Closed로 직접 전이 불허") {
            initiated.canTransitionTo(CycleState.Closed(CloseReason.NO_FILL)) shouldBe false
        }
    }

    context("Buying 상태 전이") {
        val buying1 = CycleState.Buying(1)
        val buying2 = CycleState.Buying(2)
        val buying3 = CycleState.Buying(3)

        test("다음 회차(+1)로 전이 허용") {
            buying1.canTransitionTo(CycleState.Buying(2)) shouldBe true
            buying2.canTransitionTo(CycleState.Buying(3)) shouldBe true
        }

        test("회차 건너뜀 불허") {
            buying1.canTransitionTo(CycleState.Buying(3)) shouldBe false
        }

        test("유효 범위 밖 회차는 생성 시 예외") {
            shouldThrow<IllegalArgumentException> { CycleState.Buying(0) }
            shouldThrow<IllegalArgumentException> { CycleState.Buying(4) }
        }

        test("Holding으로 전이 허용 — 3회 완료 또는 중도 익절") {
            buying3.canTransitionTo(CycleState.Holding) shouldBe true
            buying2.canTransitionTo(CycleState.Holding) shouldBe true
        }

        test("Liquidating으로 전이 허용 — 손절/취소") {
            buying1.canTransitionTo(CycleState.Liquidating(CloseReason.STOP_LOSS)) shouldBe true
        }

        test("Closed(NO_FILL)로 직행 허용 — 보유=0") {
            buying3.canTransitionTo(CycleState.Closed(CloseReason.NO_FILL)) shouldBe true
        }

        test("Closed(CANCELLED)로 직행 허용 — 보유=0 취소") {
            buying2.canTransitionTo(CycleState.Closed(CloseReason.CANCELLED)) shouldBe true
        }

        test("Closed(TAKE_PROFIT)로 직행 불허") {
            buying3.canTransitionTo(CycleState.Closed(CloseReason.TAKE_PROFIT)) shouldBe false
        }

        test("Initiated로 역전이 불허") {
            buying1.canTransitionTo(CycleState.Initiated) shouldBe false
        }
    }

    context("Holding 상태 전이") {
        val monitoring = CycleState.Holding

        test("자기 자신(Holding)으로 전이 허용 — TpStage 부분 매도 후 잔여 유지") {
            monitoring.canTransitionTo(CycleState.Holding) shouldBe true
        }

        test("Liquidating으로 전이 허용 — BE/TB/LU/MC") {
            monitoring.canTransitionTo(CycleState.Liquidating(CloseReason.BREAKEVEN)) shouldBe true
        }

        test("Closed로 직접 전이 불허") {
            monitoring.canTransitionTo(CycleState.Closed(CloseReason.TAKE_PROFIT)) shouldBe false
        }

        test("Buying으로 역전이 불허") {
            monitoring.canTransitionTo(CycleState.Buying(1)) shouldBe false
        }
    }

    context("Liquidating 상태 전이") {
        val liquidating = CycleState.Liquidating(CloseReason.STOP_LOSS)

        test("Closed로 전이 허용") {
            liquidating.canTransitionTo(CycleState.Closed(CloseReason.STOP_LOSS)) shouldBe true
        }

        test("Holding으로 역전이 불허") {
            liquidating.canTransitionTo(CycleState.Holding) shouldBe false
        }
    }

    context("Closed 상태 전이") {
        val closed = CycleState.Closed(CloseReason.TAKE_PROFIT)

        test("어떤 상태로도 전이 불허 — 종료 상태") {
            closed.canTransitionTo(CycleState.Initiated) shouldBe false
            closed.canTransitionTo(CycleState.Buying(1)) shouldBe false
            closed.canTransitionTo(CycleState.Holding) shouldBe false
            closed.canTransitionTo(CycleState.Closed(CloseReason.STOP_LOSS)) shouldBe false
        }
    }
})
