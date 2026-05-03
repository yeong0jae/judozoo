package at.backend.trading.domain.scenario

import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.price.Bar
import at.backend.trading.domain.price.PriceTick
import at.backend.trading.domain.signal.Signal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class TradingScenarioTest : FunSpec({

    val now = Instant.now()
    val stockCode = "000660"

    fun tick(price: Int) = PriceTick(stockCode, price, now)

    fun execution(executedPrice: Int, executedQty: Int, fee: Int) =
        Execution(orderId = 1L, executedQty = executedQty, executedPrice = executedPrice, fee = fee, tax = 0)

    fun cycle(
        status: TradingCycleStatus = TradingCycleStatus.HOLDING,
        buyAttempt: Int = 3,
        tpStagesFired: Int = 0b000,
        breakevenArmed: Boolean = false,
        trendBreakArmed: Boolean = false,
        stopLossPct: BigDecimal = BigDecimal("-0.02"),
        midwayProfitPct: BigDecimal = BigDecimal("3.0"),
    ) = TradingCycle(
        stockCode = stockCode,
        stockName = "SK하이닉스",
        perBuyAmount = 1_000_000,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.20"),
        midwayProfitPct = midwayProfitPct,
        breakevenThresholdPct = BigDecimal("2.0"),
        stopLossPct = stopLossPct,
        status = status,
        buyAttempt = buyAttempt,
        tpStagesFired = tpStagesFired,
        breakevenArmed = breakevenArmed,
        trendBreakArmed = trendBreakArmed,
    )

    context("시나리오 1: 정상 사이클") {
        test("3회 매수 체결 후 매수가 산정") {
            val executions = listOf(
                execution(10_000, 10, 150),
                execution(10_100, 10, 152),
                execution(10_050, 10, 151),
            )
            val buyPrice = cycle().calculateBuyPrice(executions, 0.0025)
            // 원가합계 = (100000+150) + (101000+152) + (100500+151) = 301953
            // 총수량 = 30, 원가평균 = 10065.1, 매수가 = ceil(10065.1 × 1.0025) = 10091
            buyPrice shouldBe 10_091
        }

        test("Buying(3) → Holding 전이 유효") {
            cycle(
                status = TradingCycleStatus.BUYING,
                buyAttempt = 3
            ).canTransitionTo(TradingCycleStatus.HOLDING) shouldBe true
        }

        test("Holding 상태에서 +2% 도달 → TpStage(2) 발동") {
            val buyPrice = 10_091
            // ceil(10091 × 1.02) = 10293 이상이어야 TpStage(2) 발동
            cycle().detectSignals(tick(10_293), holdingQty = 30, buyPrice = buyPrice) shouldContain Signal.TpStage(2)
        }

        test("TpStage(2) 발동 후 tpStagesFired 갱신 → 동일 단계 재발동 없음") {
            val buyPrice = 10_091
            cycle(tpStagesFired = 0b001).detectSignals(
                tick(buyPrice + (buyPrice * 0.02).toInt()),
                holdingQty = 30,
                buyPrice = buyPrice
            )
                .none { it == Signal.TpStage(2) } shouldBe true
        }

        test("+5% 도달 → TpStage(5) 발동") {
            val buyPrice = 10_091
            cycle(tpStagesFired = 0b011).detectSignals(
                tick(buyPrice + (buyPrice * 0.05).toInt() + 1),
                holdingQty = 20,
                buyPrice = buyPrice
            ) shouldContain Signal.TpStage(5)
        }

        test("TrendBreak 무장 후 봉 꺾임 → TrendBreak 발동") {
            val buyPrice = 10_091
            val prevBar = Bar(
                stockCode,
                openPrice = 10_700,
                closePrice = 11_000,
                startTime = now.minusSeconds(360),
                endTime = now.minusSeconds(180)
            )
            val currentBar = Bar(
                stockCode,
                openPrice = 10_800,
                closePrice = 10_699,
                startTime = now.minusSeconds(180),
                endTime = now.plusSeconds(1)
            )
            cycle(tpStagesFired = 0b111, trendBreakArmed = true)
                .detectSignals(
                    tick(10_699),
                    currentBar,
                    prevBar,
                    holdingQty = 8,
                    buyPrice = buyPrice
                ) shouldContainExactly listOf(Signal.TrendBreak)
        }

        test("Liquidating → Closed(TAKE_PROFIT) 전이 유효") {
            cycle(status = TradingCycleStatus.LIQUIDATING).canTransitionTo(TradingCycleStatus.CLOSED) shouldBe true
        }
    }

    context("시나리오 2: 중도 익절") {
        test("Buying(2) 상태에서 +3.5% 도달 → MidwayTakeProfit 발동") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 2)
                .detectSignals(
                    tick(10_350),
                    holdingQty = 10,
                    buyPrice = 10_000
                ) shouldContainExactly listOf(Signal.MidwayTakeProfit)
        }

        test("MidwayTakeProfit 발동 후 Buying→Holding 전이 유효") {
            cycle(
                status = TradingCycleStatus.BUYING,
                buyAttempt = 2
            ).canTransitionTo(TradingCycleStatus.HOLDING) shouldBe true
        }

        test("Holding 진입 후 잔여 수량 보유 유지") {
            cycle().detectSignals(tick(10_200), holdingQty = 10, buyPrice = 10_000) shouldContain Signal.TpStage(2)
        }
    }

    context("시나리오 3: 갭상승 복수 단계 동시 발동") {
        test("+6% 가격에서 tpStagesFired=0 → TpStage(2/3/5) 모두 발동") {
            val signals = cycle().detectSignals(tick(10_600), holdingQty = 30, buyPrice = 10_000)
            signals shouldContain Signal.TpStage(2)
            signals shouldContain Signal.TpStage(3)
            signals shouldContain Signal.TpStage(5)
            signals.size shouldBe 3
        }

        test("3단계 모두 발동 후 tpStagesFired=0b111 → holdingQty=0이면 재발동 없음") {
            cycle(tpStagesFired = 0b111).detectSignals(tick(10_600), holdingQty = 0, buyPrice = 10_000)
                .none { it is Signal.TpStage } shouldBe true
        }
    }

    context("시나리오 4: 손절 우선순위") {
        test("손절선 이하 가격에서 StopLoss만 반환") {
            cycle(tpStagesFired = 0b001).detectSignals(
                tick(9_800),
                holdingQty = 30,
                buyPrice = 10_000
            ) shouldContainExactly listOf(Signal.StopLoss)
        }
    }

    context("시나리오 5: 본전 매도 무장 후 발동") {
        test("breakevenArmed=true 상태에서 매수가 도달 → Breakeven 발동") {
            cycle(breakevenArmed = true, tpStagesFired = 0b001)
                .detectSignals(
                    tick(10_000),
                    holdingQty = 20,
                    buyPrice = 10_000
                ) shouldContainExactly listOf(Signal.Breakeven)
        }

        test("Breakeven isAlive — 매수가 이하 유지 중 true") {
            Signal.Breakeven.isAlive(
                currentPrice = 9_999,
                buyPrice = 10_000,
                currentBar = null,
                clock = now
            ) shouldBe true
            Signal.Breakeven.isAlive(
                currentPrice = 10_000,
                buyPrice = 10_000,
                currentBar = null,
                clock = now
            ) shouldBe true
        }

        test("현재가가 매수가 초과하면 Breakeven isAlive=false") {
            Signal.Breakeven.isAlive(
                currentPrice = 10_001,
                buyPrice = 10_000,
                currentBar = null,
                clock = now
            ) shouldBe false
        }

        test("Holding → Liquidating(BREAKEVEN) 전이 유효") {
            cycle(status = TradingCycleStatus.HOLDING).canTransitionTo(TradingCycleStatus.LIQUIDATING) shouldBe true
        }
    }

    context("시나리오 6: 추세 꺾임 봉 종료 후 재발동") {
        val buyPrice = 10_000
        val prevBar = Bar(
            stockCode,
            openPrice = 10_600,
            closePrice = 10_900,
            startTime = now.minusSeconds(360),
            endTime = now.minusSeconds(180)
        )

        test("봉 진행 중 TrendBreak 발동 → isAlive=true") {
            val currentBar = Bar(
                stockCode,
                openPrice = 10_700,
                closePrice = 10_599,
                startTime = now.minusSeconds(180),
                endTime = now.plusSeconds(60)
            )
            cycle(tpStagesFired = 0b111, trendBreakArmed = true)
                .detectSignals(
                    tick(10_599),
                    currentBar,
                    prevBar,
                    holdingQty = 10,
                    buyPrice = buyPrice
                ) shouldContain Signal.TrendBreak
            Signal.TrendBreak.isAlive(10_599, buyPrice, currentBar, clock = now) shouldBe true
        }

        test("봉 종료 후 isAlive=false — 재발동 대기") {
            val finishedBar = Bar(
                stockCode,
                openPrice = 10_700,
                closePrice = 10_599,
                startTime = now.minusSeconds(360),
                endTime = now.minusSeconds(180)
            )
            Signal.TrendBreak.isAlive(10_599, buyPrice, finishedBar, clock = now) shouldBe false
        }

        test("다음 봉에서 조건 재충족 시 TrendBreak 재발동") {
            val newPrevBar = Bar(
                stockCode,
                openPrice = 10_700,
                closePrice = 10_599,
                startTime = now.minusSeconds(360),
                endTime = now.minusSeconds(180)
            )
            val newCurrentBar = Bar(
                stockCode,
                openPrice = 10_650,
                closePrice = 10_699,
                startTime = now.minusSeconds(180),
                endTime = now.plusSeconds(60)
            )
            cycle(tpStagesFired = 0b111, trendBreakArmed = true)
                .detectSignals(
                    tick(10_699),
                    newCurrentBar,
                    newPrevBar,
                    holdingQty = 10,
                    buyPrice = buyPrice
                ) shouldContain Signal.TrendBreak
        }
    }

    context("시나리오 7: NO_FILL — 3회 매수 완료 후 보유=0") {
        test("3회 완료 후 holdingQty=0이면 Buying→Closed(NO_FILL) 직행 허용") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 3)
                .canTransitionTo(TradingCycleStatus.CLOSED, nextCloseReason = CloseReason.NO_FILL) shouldBe true
        }

        test("holdingQty=0이면 detectSignals는 빈 리스트") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 3)
                .detectSignals(tick(9_000), holdingQty = 0, buyPrice = 10_000).shouldBeEmpty()
        }
    }

    context("시나리오 8: 시그널 평가 보류 — holdingQty=0") {
        test("Holding 상태라도 holdingQty=0이면 모든 가격 기반 시그널 평가 안 됨") {
            cycle(breakevenArmed = true, trendBreakArmed = true)
                .detectSignals(tick(9_800), holdingQty = 0, buyPrice = 10_000).shouldBeEmpty()
        }

        test("첫 체결 발생(holdingQty>0) 시점부터 평가 시작") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 1)
                .detectSignals(tick(9_800), holdingQty = 5, buyPrice = 10_000) shouldContain Signal.StopLoss
        }
    }
})
