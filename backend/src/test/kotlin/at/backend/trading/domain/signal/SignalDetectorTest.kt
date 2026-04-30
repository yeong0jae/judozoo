package at.backend.trading.domain.signal

import at.backend.trading.domain.Bar
import at.backend.trading.domain.PriceTick
import at.backend.trading.domain.cycle.CycleState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant

class SignalDetectorTest : FunSpec({

    val now = Instant.now()
    val buyPrice = 10_000

    fun snapshot(
        state: CycleState = CycleState.Monitoring,
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

    context("보유 수량 = 0 → 가격 기반 시그널 평가 보류") {
        test("holdingQty=0이면 빈 리스트 반환") {
            val result = SignalDetector.detect(tick(9_000), snapshot(holdingQty = 0))
            result.shouldBeEmpty()
        }
    }

    context("StopLoss 우선순위 처리") {
        test("StopLoss + TpStage 동시 조건 → StopLoss만 반환") {
            // 가격 9800: 손절선(-2%) 이하이면서 이전에 TpStage가 남아 있는 상태
            val snap = snapshot(tpStagesFired = 0b001)  // 2%는 이미 발동, 3%/5%는 미발동
            // 9800은 손절(9800)이고 3% 기준(10300)을 만족하지 않음 → StopLoss만
            val result = SignalDetector.detect(tick(9_800), snap)
            result shouldContainExactly listOf(Signal.StopLoss)
        }

        test("StopLoss가 없으면 다른 시그널들이 정상 반환") {
            val snap = snapshot(tpStagesFired = 0b000)
            val result = SignalDetector.detect(tick(10_200), snap)
            result.any { it is Signal.TpStage } shouldBe true
            result.none { it is Signal.StopLoss } shouldBe true
        }
    }

    context("Monitoring 상태 시그널 평가") {
        test("TpStage 2% 미발동 상태에서 현재가 10200 → TpStage(2) 반환") {
            val result = SignalDetector.detect(tick(10_200), snapshot(tpStagesFired = 0b000))
            result shouldContainExactly listOf(Signal.TpStage(2))
        }

        test("tpStagesFired 비트 중복 발동 방지 — 2% 이미 발동 상태에서 10200 → 빈 리스트") {
            val result = SignalDetector.detect(tick(10_200), snapshot(tpStagesFired = 0b001))
            result.shouldBeEmpty()
        }

        test("Breakeven 무장 상태에서 매수가 도달 → Breakeven 반환") {
            val result = SignalDetector.detect(tick(10_000), snapshot(breakevenArmed = true))
            result shouldContainExactly listOf(Signal.Breakeven)
        }

        test("Breakeven 미무장 상태에서는 미발동") {
            val result = SignalDetector.detect(tick(10_000), snapshot(breakevenArmed = false))
            result.none { it is Signal.Breakeven } shouldBe true
        }

        test("TrendBreak 무장 + currentBar/prevBar 조건 충족 → TrendBreak 반환") {
            val prevBar = Bar("000660", openPrice = 10_500, closePrice = 10_800,
                startTime = now.minusSeconds(360), endTime = now.minusSeconds(180))
            val currentBar = Bar("000660", openPrice = 10_600, closePrice = 10_499,
                startTime = now.minusSeconds(180), endTime = now.plusSeconds(1))
            // tpStagesFired=0b111: 2/3/5% 단계 모두 이미 발동 → TrendBreak만 남음
            val result = SignalDetector.detect(
                tick(10_499), snapshot(trendBreakArmed = true, tpStagesFired = 0b111),
                currentBar = currentBar, prevBar = prevBar,
            )
            result shouldContainExactly listOf(Signal.TrendBreak)
        }

        test("currentBar 없으면 TrendBreak 미발동") {
            val result = SignalDetector.detect(
                tick(10_400), snapshot(trendBreakArmed = true),
                currentBar = null, prevBar = null,
            )
            result.none { it is Signal.TrendBreak } shouldBe true
        }
    }

    context("Buying 상태 시그널 평가") {
        test("2회차 중 손절가 이하 → StopLoss 반환") {
            val snap = snapshot(state = CycleState.Buying(2), buyAttempt = 2)
            val result = SignalDetector.detect(tick(9_800), snap)
            result shouldContainExactly listOf(Signal.StopLoss)
        }

        test("2회차 중 중도 익절 조건(+3%) 충족 → MidwayTakeProfit 반환") {
            val snap = snapshot(state = CycleState.Buying(2), buyAttempt = 2)
            val result = SignalDetector.detect(tick(10_300), snap)
            result shouldContainExactly listOf(Signal.MidwayTakeProfit)
        }

        test("3회차 완료(buyAttempt=3)에서는 MidwayTakeProfit 미발동") {
            val snap = snapshot(state = CycleState.Buying(3), buyAttempt = 3)
            val result = SignalDetector.detect(tick(10_300), snap)
            result.none { it is Signal.MidwayTakeProfit } shouldBe true
        }
    }

    context("Initiated / Liquidating / Closed 상태에서는 평가 없음") {
        test("Initiated 상태에서는 빈 리스트") {
            val result = SignalDetector.detect(tick(9_000), snapshot(state = CycleState.Initiated))
            result.shouldBeEmpty()
        }
    }
})
