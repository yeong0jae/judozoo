package at.backend.trading.domain.scenario

import at.backend.trading.domain.Bar
import at.backend.trading.domain.CloseReason
import at.backend.trading.domain.PriceTick
import at.backend.trading.domain.cycle.CycleState
import at.backend.trading.domain.rule.Execution
import at.backend.trading.domain.rule.TradingRules
import at.backend.trading.domain.signal.CycleSnapshot
import at.backend.trading.domain.signal.Signal
import at.backend.trading.domain.signal.SignalDetector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant

class TradingScenarioTest : FunSpec({

    val now = Instant.now()
    val stockCode = "000660"

    fun tick(price: Int) = PriceTick(stockCode, price, now)

    fun baseSnapshot(
        state: CycleState,
        holdingQty: Int,
        buyPrice: Int,
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

    context("시나리오 1: 정상 사이클") {
        // 3회 매수 → TpStage(2/3/5%) 단계별 발동 → TrendBreak 잔여 매도 → Closed(TAKE_PROFIT)
        test("3회 매수 체결 후 매수가 산정") {
            val executions = listOf(
                Execution(10_000, 10, 150),
                Execution(10_100, 10, 152),
                Execution(10_050, 10, 151),
            )
            val buyPrice = TradingRules.calcBuyPrice(executions, 0.0025)
            // 원가합계 = (100000+150) + (101000+152) + (100500+151) = 301953
            // 총수량 = 30, 원가평균 = 10065.1, 매수가 = ceil(10065.1 × 1.0025) = 10091
            buyPrice shouldBe 10_091
        }

        test("Buying(3) → Monitoring 전이 유효") {
            CycleState.Buying(3).canTransitionTo(CycleState.Monitoring) shouldBe true
        }

        test("Monitoring 상태에서 +2% 도달 → TpStage(2) 발동") {
            val buyPrice = 10_091
            val snap = baseSnapshot(CycleState.Monitoring, holdingQty = 30, buyPrice = buyPrice)
            // ceil(10091 × 1.02) = 10293 이상이어야 TpStage(2) 발동
            val signals = SignalDetector.detect(tick(10_293), snap)
            signals shouldContain Signal.TpStage(2)
        }

        test("TpStage(2) 발동 후 tpStagesFired 갱신 → 동일 단계 재발동 없음") {
            val buyPrice = 10_091
            val snap = baseSnapshot(CycleState.Monitoring, 30, buyPrice, tpStagesFired = 0b001)
            val signals = SignalDetector.detect(tick(buyPrice + (buyPrice * 0.02).toInt()), snap)
            signals.none { it == Signal.TpStage(2) } shouldBe true
        }

        test("+5% 도달 → trendBreakArmed 무장 조건 충족 (TpStage(5) 발동)") {
            val buyPrice = 10_091
            val snap = baseSnapshot(CycleState.Monitoring, 20, buyPrice, tpStagesFired = 0b011)
            val signals = SignalDetector.detect(tick(buyPrice + (buyPrice * 0.05).toInt() + 1), snap)
            signals shouldContain Signal.TpStage(5)
        }

        test("TrendBreak 무장 후 봉 꺾임 → TrendBreak 발동") {
            val buyPrice = 10_091
            val prevBar = Bar(stockCode, openPrice = 10_700, closePrice = 11_000,
                startTime = now.minusSeconds(360), endTime = now.minusSeconds(180))
            val currentBar = Bar(stockCode, openPrice = 10_800, closePrice = 10_699,
                startTime = now.minusSeconds(180), endTime = now.plusSeconds(1))
            val snap = baseSnapshot(CycleState.Monitoring, 8, buyPrice,
                tpStagesFired = 0b111, trendBreakArmed = true)
            val signals = SignalDetector.detect(tick(10_699), snap, currentBar, prevBar)
            signals shouldContainExactly listOf(Signal.TrendBreak)
        }

        test("Liquidating → Closed(TAKE_PROFIT) 전이 유효") {
            val liquidating = CycleState.Liquidating(CloseReason.TAKE_PROFIT)
            liquidating.canTransitionTo(CycleState.Closed(CloseReason.TAKE_PROFIT)) shouldBe true
        }
    }

    context("시나리오 2: 중도 익절") {
        // 매수 2회차 중 +3.5% 도달 → MidwayTakeProfit 발동 → Buying→Monitoring 전이
        test("Buying(2) 상태에서 +3.5% 도달 → MidwayTakeProfit 발동") {
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Buying(2), holdingQty = 10, buyPrice = buyPrice, buyAttempt = 2)
            val signals = SignalDetector.detect(tick(10_350), snap)
            signals shouldContainExactly listOf(Signal.MidwayTakeProfit)
        }

        test("MidwayTakeProfit 발동 후 Buying→Monitoring 전이 유효") {
            CycleState.Buying(2).canTransitionTo(CycleState.Monitoring) shouldBe true
        }

        test("Monitoring 진입 후 잔여 수량 보유 유지 (holdingQty > 0)") {
            // 중도 익절 후 잔여 수량이 남아 Monitoring에서 계속 시그널 평가
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Monitoring, holdingQty = 10, buyPrice = buyPrice)
            val signals = SignalDetector.detect(tick(10_200), snap)
            signals shouldContain Signal.TpStage(2)
        }
    }

    context("시나리오 3: 갭상승 복수 단계 동시 발동") {
        // 시초가 +6% → TpStage(2/3/5%) 한 번에 3개 발동
        test("+6% 가격에서 tpStagesFired=0 → TpStage(2/3/5) 모두 발동") {
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Monitoring, holdingQty = 30, buyPrice = buyPrice)
            val signals = SignalDetector.detect(tick(10_600), snap)
            signals shouldContain Signal.TpStage(2)
            signals shouldContain Signal.TpStage(3)
            signals shouldContain Signal.TpStage(5)
            signals.size shouldBe 3
        }

        test("3단계 모두 발동 후 tpStagesFired=0b111 → 재발동 없음") {
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Monitoring, 0, buyPrice, tpStagesFired = 0b111)
            // holdingQty=0이므로 평가 보류
            val signals = SignalDetector.detect(tick(10_600), snap)
            signals.none { it is Signal.TpStage } shouldBe true
        }
    }

    context("시나리오 4: 손절 우선순위") {
        // TpStage 무장 상태(3% 미발동) + 가격 -2% 동시 → StopLoss 우선
        test("손절선 이하 가격에서 TpStage 조건도 해당되지 않으므로 StopLoss만 반환") {
            val buyPrice = 10_000
            // 9800은 손절선(-2%). TpStage는 10200/10300/10500 이상이어야 하므로 중복 없음
            val snap = baseSnapshot(CycleState.Monitoring, holdingQty = 30, buyPrice = buyPrice,
                tpStagesFired = 0b001)  // 2%는 발동, 3%/5% 미발동
            val signals = SignalDetector.detect(tick(9_800), snap)
            signals shouldContainExactly listOf(Signal.StopLoss)
        }

        test("tpStagesFired 상태와 무관하게 손절 가격 도달 시 StopLoss만 반환") {
            // 손절선(-2%) 9800에서 미발동 TpStage가 있어도 StopLoss만 반환
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Monitoring, holdingQty = 30, buyPrice = buyPrice,
                tpStagesFired = 0b000)  // 모든 단계 미발동 — 그래도 9800은 TpStage 조건 미달
            val signals = SignalDetector.detect(tick(9_800), snap)
            signals shouldContainExactly listOf(Signal.StopLoss)
        }
    }
})
