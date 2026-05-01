package at.backend.trading.domain.cycle

import at.backend.trading.domain.Bar
import at.backend.trading.domain.PriceTick
import at.backend.trading.domain.signal.Signal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant

class CycleSnapshotTest : FunSpec({

    val now = Instant.now()
    val buyPrice = 10_000

    fun snapshot(
        state: CycleState = CycleState.Holding,
        holdingQty: Int = 50,
        tpStagesFired: Int = 0b000,
        breakevenArmed: Boolean = false,
        trendBreakArmed: Boolean = false,
        buyAttempt: Int = 3,
        stopLossPct: Double = -0.02,
        midwayProfitPct: Double = 3.0,
    ) = CycleSnapshot(
        state = state,
        holdingQty = holdingQty,
        buyPrice = buyPrice,
        tpStagesFired = tpStagesFired,
        breakevenArmed = breakevenArmed,
        trendBreakArmed = trendBreakArmed,
        buyAttempt = buyAttempt,
        stopLossPct = stopLossPct,
        midwayProfitPct = midwayProfitPct,
    )

    fun tick(price: Int) = PriceTick(stockCode = "000660", price = price, timestamp = now)

    context("손절 조건 평가") {
        test("현재가가 손절가 이하이면 발동") {
            snapshot().isStopLossTriggered(9_800) shouldBe true
            snapshot().isStopLossTriggered(9_799) shouldBe true
        }

        test("현재가가 손절가 위이면 미발동") {
            snapshot().isStopLossTriggered(9_801) shouldBe false
        }
    }

    context("중도 익절 조건 평가") {
        test("현재가가 목표가 이상이면 발동") {
            snapshot().isMidwayTakeProfitTriggered(10_300) shouldBe true
            snapshot().isMidwayTakeProfitTriggered(10_500) shouldBe true
        }

        test("현재가가 목표가 미만이면 미발동") {
            snapshot().isMidwayTakeProfitTriggered(10_299) shouldBe false
        }
    }

    context("단계 익절 조건 평가") {
        test("미발동 단계가 목표가에 도달하면 발동") {
            snapshot().isTpStageTriggered(10_200, stagePct = 2) shouldBe true
            snapshot().isTpStageTriggered(10_500, stagePct = 5) shouldBe true
        }

        test("이미 발동된 단계는 재발동하지 않음") {
            snapshot(tpStagesFired = 0b001).isTpStageTriggered(10_200, stagePct = 2) shouldBe false
        }

        test("일부 단계만 발동된 상태에서 미발동 단계 평가") {
            snapshot(tpStagesFired = 0b001).isTpStageTriggered(10_350, stagePct = 3) shouldBe true
        }

        test("기준가 미달이면 미발동") {
            snapshot().isTpStageTriggered(10_199, stagePct = 2) shouldBe false
        }
    }

    context("본전 매도 조건 평가") {
        test("무장 상태에서 현재가가 매수가 이하이면 발동") {
            snapshot(breakevenArmed = true).isBreakevenTriggered(10_000) shouldBe true
            snapshot(breakevenArmed = true).isBreakevenTriggered(9_999) shouldBe true
        }

        test("무장 상태에서 현재가가 매수가 위이면 미발동") {
            snapshot(breakevenArmed = true).isBreakevenTriggered(10_001) shouldBe false
        }

        test("미무장 상태에서는 항상 미발동") {
            snapshot(breakevenArmed = false).isBreakevenTriggered(9_000) shouldBe false
        }
    }

    context("추세 꺾임 조건 평가") {
        val prevBar = Bar("000660", openPrice = 10_500, closePrice = 10_800, startTime = now.minusSeconds(360), endTime = now.minusSeconds(180))
        val currentBar = Bar("000660", openPrice = 10_600, closePrice = 10_499, startTime = now.minusSeconds(180), endTime = now)

        test("무장 상태에서 현재봉 종가가 직전봉 시가보다 낮으면 발동") {
            snapshot(trendBreakArmed = true).isTrendBreakTriggered(currentBar, prevBar) shouldBe true
        }

        test("무장 상태에서 현재봉 종가가 직전봉 시가 이상이면 미발동") {
            val strongBar = currentBar.copy(closePrice = 10_500)
            snapshot(trendBreakArmed = true).isTrendBreakTriggered(strongBar, prevBar) shouldBe false
        }

        test("미무장 상태에서는 항상 미발동") {
            snapshot(trendBreakArmed = false).isTrendBreakTriggered(currentBar, prevBar) shouldBe false
        }
    }

    context("분할 매도 수량 산출") {
        test("정수 절사: 50주 × 20% → 10주 + 잔여 40주") {
            snapshot(holdingQty = 50).splitSellQty(0.20) shouldBe Pair(10, 40)
        }

        test("홀수 수량 절사: 51주 × 20% → 10주 + 잔여 41주") {
            snapshot(holdingQty = 51).splitSellQty(0.20) shouldBe Pair(10, 41)
        }

        test("100% 매도 시 잔여 0") {
            snapshot(holdingQty = 5).splitSellQty(1.0) shouldBe Pair(5, 0)
        }
    }

    context("보유 수량 0이면 가격 기반 시그널 평가 보류") {
        test("보유 0이면 빈 리스트 반환") {
            snapshot(holdingQty = 0).detectSignals(tick(9_000)).shouldBeEmpty()
        }
    }

    context("StopLoss 발동 시 다른 시그널은 모두 무시") {
        test("StopLoss와 다른 시그널이 동시 조건이면 StopLoss만 반환") {
            val result = snapshot(tpStagesFired = 0b001).detectSignals(tick(9_800))
            result shouldContainExactly listOf(Signal.StopLoss)
        }

        test("StopLoss가 없으면 다른 시그널들이 정상 반환") {
            val result = snapshot().detectSignals(tick(10_200))
            result.any { it is Signal.TpStage } shouldBe true
            result.none { it is Signal.StopLoss } shouldBe true
        }
    }

    context("Holding 상태 시그널 감지") {
        test("미발동 단계가 목표가 도달 시 TpStage 반환") {
            val result = snapshot().detectSignals(tick(10_200))
            result shouldContainExactly listOf(Signal.TpStage(2))
        }

        test("이미 발동된 단계는 재감지하지 않음") {
            snapshot(tpStagesFired = 0b001).detectSignals(tick(10_200)).shouldBeEmpty()
        }

        test("Breakeven 무장 + 매수가 도달 시 Breakeven 반환") {
            snapshot(breakevenArmed = true).detectSignals(tick(10_000)) shouldContainExactly listOf(Signal.Breakeven)
        }

        test("Breakeven 미무장 시 미발동") {
            snapshot(breakevenArmed = false).detectSignals(tick(10_000)).none { it is Signal.Breakeven } shouldBe true
        }

        test("TrendBreak 무장 + 봉 조건 충족 시 TrendBreak 반환") {
            val prevBar = Bar(
                "000660", openPrice = 10_500, closePrice = 10_800,
                startTime = now.minusSeconds(360), endTime = now.minusSeconds(180)
            )
            val currentBar = Bar(
                "000660", openPrice = 10_600, closePrice = 10_499,
                startTime = now.minusSeconds(180), endTime = now.plusSeconds(1)
            )
            val result = snapshot(trendBreakArmed = true, tpStagesFired = 0b111)
                .detectSignals(tick(10_499), currentBar = currentBar, prevBar = prevBar)
            result shouldContainExactly listOf(Signal.TrendBreak)
        }

        test("봉 정보 없이 호출하면 TrendBreak 평가 자체가 안 됨") {
            snapshot(trendBreakArmed = true).detectSignals(tick(10_400)).none { it is Signal.TrendBreak } shouldBe true
        }
    }

    context("Buying 상태 시그널 감지") {
        test("매수 진행 중 손절가 이하 시 StopLoss 반환") {
            val snap = snapshot(state = CycleState.Buying(2), buyAttempt = 2)
            snap.detectSignals(tick(9_800)) shouldContainExactly listOf(Signal.StopLoss)
        }

        test("매수 진행 중 중도 익절 조건 충족 시 MidwayTakeProfit 반환") {
            val snap = snapshot(state = CycleState.Buying(2), buyAttempt = 2)
            snap.detectSignals(tick(10_300)) shouldContainExactly listOf(Signal.MidwayTakeProfit)
        }

        test("3회차 완료 후에는 MidwayTakeProfit 미발동") {
            val snap = snapshot(state = CycleState.Buying(3), buyAttempt = 3)
            snap.detectSignals(tick(10_300)).none { it is Signal.MidwayTakeProfit } shouldBe true
        }
    }

    context("Buying / Holding 외 상태에서는 가격 기반 시그널 평가 안 함") {
        test("Initiated 상태에서는 빈 리스트") {
            snapshot(state = CycleState.Initiated).detectSignals(tick(9_000)).shouldBeEmpty()
        }
    }
})
