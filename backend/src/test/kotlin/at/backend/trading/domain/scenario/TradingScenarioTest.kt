package at.backend.trading.domain.scenario

import at.backend.trading.domain.Bar
import at.backend.trading.domain.PriceTick
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.CycleSnapshot
import at.backend.trading.domain.cycle.CycleState
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.execution.Executions
import at.backend.trading.domain.signal.Signal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
            val executions = Executions(
                listOf(
                    Execution(10_000, 10, 150),
                    Execution(10_100, 10, 152),
                    Execution(10_050, 10, 151),
                )
            )
            val buyPrice = executions.calculateBuyPrice(0.0025)
            // 원가합계 = (100000+150) + (101000+152) + (100500+151) = 301953
            // 총수량 = 30, 원가평균 = 10065.1, 매수가 = ceil(10065.1 × 1.0025) = 10091
            buyPrice shouldBe 10_091
        }

        test("Buying(3) → Holding 전이 유효") {
            CycleState.Buying(3).canTransitionTo(CycleState.Holding) shouldBe true
        }

        test("Holding 상태에서 +2% 도달 → TpStage(2) 발동") {
            val buyPrice = 10_091
            val snap = baseSnapshot(CycleState.Holding, holdingQty = 30, buyPrice = buyPrice)
            // ceil(10091 × 1.02) = 10293 이상이어야 TpStage(2) 발동
            val signals = snap.detectSignals(tick(10_293))
            signals shouldContain Signal.TpStage(2)
        }

        test("TpStage(2) 발동 후 tpStagesFired 갱신 → 동일 단계 재발동 없음") {
            val buyPrice = 10_091
            val snap = baseSnapshot(CycleState.Holding, 30, buyPrice, tpStagesFired = 0b001)
            val signals = snap.detectSignals(tick(buyPrice + (buyPrice * 0.02).toInt()))
            signals.none { it == Signal.TpStage(2) } shouldBe true
        }

        test("+5% 도달 → trendBreakArmed 무장 조건 충족 (TpStage(5) 발동)") {
            val buyPrice = 10_091
            val snap = baseSnapshot(CycleState.Holding, 20, buyPrice, tpStagesFired = 0b011)
            val signals = snap.detectSignals(tick(buyPrice + (buyPrice * 0.05).toInt() + 1))
            signals shouldContain Signal.TpStage(5)
        }

        test("TrendBreak 무장 후 봉 꺾임 → TrendBreak 발동") {
            val buyPrice = 10_091
            val prevBar = Bar(
                stockCode, openPrice = 10_700, closePrice = 11_000,
                startTime = now.minusSeconds(360), endTime = now.minusSeconds(180)
            )
            val currentBar = Bar(
                stockCode, openPrice = 10_800, closePrice = 10_699,
                startTime = now.minusSeconds(180), endTime = now.plusSeconds(1)
            )
            val snap = baseSnapshot(
                CycleState.Holding, 8, buyPrice,
                tpStagesFired = 0b111, trendBreakArmed = true
            )
            val signals = snap.detectSignals(tick(10_699), currentBar, prevBar)
            signals shouldContainExactly listOf(Signal.TrendBreak)
        }

        test("Liquidating → Closed(TAKE_PROFIT) 전이 유효") {
            val liquidating = CycleState.Liquidating(CloseReason.TAKE_PROFIT)
            liquidating.canTransitionTo(CycleState.Closed(CloseReason.TAKE_PROFIT)) shouldBe true
        }
    }

    context("시나리오 2: 중도 익절") {
        // 매수 2회차 중 +3.5% 도달 → MidwayTakeProfit 발동 → Buying→Holding 전이
        test("Buying(2) 상태에서 +3.5% 도달 → MidwayTakeProfit 발동") {
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Buying(2), holdingQty = 10, buyPrice = buyPrice, buyAttempt = 2)
            val signals = snap.detectSignals(tick(10_350))
            signals shouldContainExactly listOf(Signal.MidwayTakeProfit)
        }

        test("MidwayTakeProfit 발동 후 Buying→Holding 전이 유효") {
            CycleState.Buying(2).canTransitionTo(CycleState.Holding) shouldBe true
        }

        test("Holding 진입 후 잔여 수량 보유 유지 (holdingQty > 0)") {
            // 중도 익절 후 잔여 수량이 남아 Holding에서 계속 시그널 평가
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Holding, holdingQty = 10, buyPrice = buyPrice)
            val signals = snap.detectSignals(tick(10_200))
            signals shouldContain Signal.TpStage(2)
        }
    }

    context("시나리오 3: 갭상승 복수 단계 동시 발동") {
        // 시초가 +6% → TpStage(2/3/5%) 한 번에 3개 발동
        test("+6% 가격에서 tpStagesFired=0 → TpStage(2/3/5) 모두 발동") {
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Holding, holdingQty = 30, buyPrice = buyPrice)
            val signals = snap.detectSignals(tick(10_600))
            signals shouldContain Signal.TpStage(2)
            signals shouldContain Signal.TpStage(3)
            signals shouldContain Signal.TpStage(5)
            signals.size shouldBe 3
        }

        test("3단계 모두 발동 후 tpStagesFired=0b111 → 재발동 없음") {
            val buyPrice = 10_000
            val snap = baseSnapshot(CycleState.Holding, 0, buyPrice, tpStagesFired = 0b111)
            // holdingQty=0이므로 평가 보류
            val signals = snap.detectSignals(tick(10_600))
            signals.none { it is Signal.TpStage } shouldBe true
        }
    }

    context("시나리오 4: 손절 우선순위") {
        // TpStage 무장 상태(3% 미발동) + 가격 -2% 동시 → StopLoss 우선
        test("손절선 이하 가격에서 TpStage 조건도 해당되지 않으므로 StopLoss만 반환") {
            val buyPrice = 10_000
            // 9800은 손절선(-2%). TpStage는 10200/10300/10500 이상이어야 하므로 중복 없음
            val snap = baseSnapshot(
                CycleState.Holding, holdingQty = 30, buyPrice = buyPrice,
                tpStagesFired = 0b001
            )  // 2%는 발동, 3%/5% 미발동
            val signals = snap.detectSignals(tick(9_800))
            signals shouldContainExactly listOf(Signal.StopLoss)
        }

        test("tpStagesFired 상태와 무관하게 손절 가격 도달 시 StopLoss만 반환") {
            // 손절선(-2%) 9800에서 미발동 TpStage가 있어도 StopLoss만 반환
            val buyPrice = 10_000
            val snap = baseSnapshot(
                CycleState.Holding, holdingQty = 30, buyPrice = buyPrice,
                tpStagesFired = 0b000
            )  // 모든 단계 미발동 — 그래도 9800은 TpStage 조건 미달
            val signals = snap.detectSignals(tick(9_800))
            signals shouldContainExactly listOf(Signal.StopLoss)
        }
    }

    context("시나리오 5: 본전 매도 무장 후 발동") {
        // +2% 도달(breakevenArmed=true) → 매수가 도달 → Breakeven 발동
        test("+2% 도달 후 breakevenArmed=true 설정") {
            val buyPrice = 10_000
            // +2% 도달: breakevenArmed 무장 조건 충족 (도메인 외부에서 armed=true로 설정)
            val snap = baseSnapshot(
                CycleState.Holding, holdingQty = 20, buyPrice = buyPrice,
                breakevenArmed = true, tpStagesFired = 0b001
            )
            // 매수가 도달 → Breakeven 발동
            val signals = snap.detectSignals(tick(10_000))
            signals shouldContainExactly listOf(Signal.Breakeven)
        }

        test("Breakeven 발동 후 isAlive — 매수가 이하 유지 중 true") {
            Signal.Breakeven.isAlive(currentPrice = 9_999, buyPrice = 10_000, currentBar = null) shouldBe true
            Signal.Breakeven.isAlive(currentPrice = 10_000, buyPrice = 10_000, currentBar = null) shouldBe true
        }

        test("현재가가 매수가 초과하면 Breakeven isAlive=false") {
            Signal.Breakeven.isAlive(currentPrice = 10_001, buyPrice = 10_000, currentBar = null) shouldBe false
        }

        test("Holding → Liquidating(BREAKEVEN) 전이 유효") {
            CycleState.Holding.canTransitionTo(CycleState.Liquidating(CloseReason.BREAKEVEN)) shouldBe true
        }
    }

    context("시나리오 6: 추세 꺾임 봉 종료 후 재발동") {
        val buyPrice = 10_000
        val prevBar = Bar(
            stockCode, openPrice = 10_600, closePrice = 10_900,
            startTime = now.minusSeconds(360), endTime = now.minusSeconds(180)
        )

        test("봉 진행 중 TrendBreak 발동 → isAlive=true") {
            val currentBar = Bar(
                stockCode, openPrice = 10_700, closePrice = 10_599,
                startTime = now.minusSeconds(180), endTime = now.plusSeconds(60)
            )
            val snap = baseSnapshot(
                CycleState.Holding, 10, buyPrice,
                tpStagesFired = 0b111, trendBreakArmed = true
            )
            val signals = snap.detectSignals(tick(10_599), currentBar, prevBar)
            signals shouldContain Signal.TrendBreak
            Signal.TrendBreak.isAlive(10_599, buyPrice, currentBar, clock = now) shouldBe true
        }

        test("봉 종료 후 isAlive=false — 재발동 대기") {
            val finishedBar = Bar(
                stockCode, openPrice = 10_700, closePrice = 10_599,
                startTime = now.minusSeconds(360), endTime = now.minusSeconds(180)
            )
            Signal.TrendBreak.isAlive(10_599, buyPrice, finishedBar, clock = now) shouldBe false
        }

        test("다음 봉에서 조건 재충족 시 TrendBreak 재발동") {
            val newPrevBar = Bar(
                stockCode, openPrice = 10_700, closePrice = 10_599,
                startTime = now.minusSeconds(360), endTime = now.minusSeconds(180)
            )
            val newCurrentBar = Bar(
                stockCode, openPrice = 10_650, closePrice = 10_699,
                startTime = now.minusSeconds(180), endTime = now.plusSeconds(60)
            )
            // newCurrentBar.closePrice(10699) < newPrevBar.openPrice(10700) → TrendBreak 조건 충족
            val snap = baseSnapshot(
                CycleState.Holding, 10, buyPrice,
                tpStagesFired = 0b111, trendBreakArmed = true
            )
            val signals = snap.detectSignals(tick(10_699), newCurrentBar, newPrevBar)
            signals shouldContain Signal.TrendBreak
        }
    }

    context("시나리오 7: NO_FILL — 3회 매수 완료 후 보유=0") {
        test("3회 완료 후 holdingQty=0이면 Buying→Closed(NO_FILL) 직행 허용") {
            CycleState.Buying(3).canTransitionTo(CycleState.Closed(CloseReason.NO_FILL)) shouldBe true
        }

        test("Buying→Liquidating 후 Closed 경로는 허용") {
            CycleState.Buying(3).canTransitionTo(CycleState.Liquidating(CloseReason.NO_FILL)) shouldBe true
        }

        test("holdingQty=0이면 detectSignals는 빈 리스트 — 매도 없이 Closed 직행") {
            val snap = baseSnapshot(CycleState.Buying(3), holdingQty = 0, buyPrice = 10_000, buyAttempt = 3)
            snap.detectSignals(tick(9_000)).shouldBeEmpty()
        }
    }

    context("시나리오 8: 시그널 평가 보류 — holdingQty=0") {
        test("Holding 상태라도 holdingQty=0이면 모든 가격 기반 시그널 평가 안 됨") {
            val buyPrice = 10_000
            val snap = baseSnapshot(
                CycleState.Holding, holdingQty = 0, buyPrice = buyPrice,
                breakevenArmed = true, trendBreakArmed = true
            )
            // 손절가(9800)에서도 빈 리스트
            snap.detectSignals(tick(9_800)).shouldBeEmpty()
        }

        test("Buying 상태 holdingQty=0 — 손절 조건이어도 빈 리스트") {
            val snap = baseSnapshot(CycleState.Buying(1), holdingQty = 0, buyPrice = 10_000, buyAttempt = 1)
            snap.detectSignals(tick(9_000)).shouldBeEmpty()
        }

        test("첫 체결 발생(holdingQty>0) 시점부터 평가 시작") {
            val snap = baseSnapshot(CycleState.Buying(1), holdingQty = 5, buyPrice = 10_000, buyAttempt = 1)
            val signals = snap.detectSignals(tick(9_800))
            signals shouldContain Signal.StopLoss
        }
    }
})
