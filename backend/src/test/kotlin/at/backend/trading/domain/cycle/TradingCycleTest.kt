package at.backend.trading.domain.cycle

import at.backend.market.domain.Bar
import at.backend.market.domain.PriceTick
import at.backend.trading.domain.AlreadyClosedException
import at.backend.trading.domain.signal.Signal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class TradingCycleTest : FunSpec({

    val now = Instant.now()
    val holdingQty = 50
    val buyPrice = 10_000

    fun cycle(
        status: TradingCycleStatus = TradingCycleStatus.HOLDING,
        tpStagesFired: Int = 0b000,
        breakevenArmed: Boolean = false,
        trendBreakArmed: Boolean = false,
        buyAttempt: Int = 3,
        stopLossPct: BigDecimal = BigDecimal("-2.0"),
        midwayProfitPct: BigDecimal = BigDecimal("3.0"),
    ) = TradingCycle(
        stockCode = "000660",
        stockName = "SK하이닉스",
        perBuyAmount = 1_000_000,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.20"),
        midwayProfitPct = midwayProfitPct,
        breakevenThresholdPct = BigDecimal("2.0"),
        stopLossPct = stopLossPct,
        status = status,
        buyAttempt = buyAttempt,
        breakevenArmed = breakevenArmed,
        trendBreakArmed = trendBreakArmed,
        tpStagesFired = tpStagesFired,
    )

    fun tick(price: Int) = PriceTick(stockCode = "000660", price = price, timestamp = now)

    context("불변식") {
        test("TP 단계 비트플래그가 범위를 벗어나면 예외") {
            shouldThrow<IllegalArgumentException> { cycle(tpStagesFired = -1) }
            shouldThrow<IllegalArgumentException> { cycle(tpStagesFired = 0b1000) }
        }

        test("손절 비율이 0 이상이면 예외") {
            shouldThrow<IllegalArgumentException> { cycle(stopLossPct = BigDecimal("0.0")) }
            shouldThrow<IllegalArgumentException> { cycle(stopLossPct = BigDecimal("0.02")) }
        }

        test("중도 익절 비율이 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> { cycle(midwayProfitPct = BigDecimal("0.0")) }
            shouldThrow<IllegalArgumentException> { cycle(midwayProfitPct = BigDecimal("-1.0")) }
        }

        test("분할 매도 수량 산출 시 보유 수량이 음수이면 예외") {
            shouldThrow<IllegalArgumentException> { cycle().splitSellQty(-1) }
        }
    }

    context("손절 조건 평가") {
        test("현재가가 손절가 이하이면 발동") {
            cycle().isStopLossTriggered(9_800, buyPrice) shouldBe true
            cycle().isStopLossTriggered(9_799, buyPrice) shouldBe true
        }

        test("현재가가 손절가 위이면 미발동") {
            cycle().isStopLossTriggered(9_801, buyPrice) shouldBe false
        }
    }

    context("중도 익절 조건 평가") {
        test("현재가가 목표가 이상이면 발동") {
            cycle().isMidwayTakeProfitTriggered(10_300, buyPrice) shouldBe true
            cycle().isMidwayTakeProfitTriggered(10_500, buyPrice) shouldBe true
        }

        test("현재가가 목표가 미만이면 미발동") {
            cycle().isMidwayTakeProfitTriggered(10_299, buyPrice) shouldBe false
        }
    }

    context("단계 익절 조건 평가") {
        test("미발동 단계가 목표가에 도달하면 발동") {
            cycle().isTpStageTriggered(10_200, stagePct = 2, buyPrice) shouldBe true
            cycle().isTpStageTriggered(10_500, stagePct = 5, buyPrice) shouldBe true
        }

        test("이미 발동된 단계는 재발동하지 않음") {
            cycle(tpStagesFired = 0b001).isTpStageTriggered(10_200, stagePct = 2, buyPrice) shouldBe false
        }

        test("일부 단계만 발동된 상태에서 미발동 단계 평가") {
            cycle(tpStagesFired = 0b001).isTpStageTriggered(10_350, stagePct = 3, buyPrice) shouldBe true
        }

        test("기준가 미달이면 미발동") {
            cycle().isTpStageTriggered(10_199, stagePct = 2, buyPrice) shouldBe false
        }
    }

    context("본전 매도 조건 평가") {
        test("무장 상태에서 현재가가 매수가 이하이면 발동") {
            cycle(breakevenArmed = true).isBreakevenTriggered(10_000, buyPrice) shouldBe true
            cycle(breakevenArmed = true).isBreakevenTriggered(9_999, buyPrice) shouldBe true
        }

        test("무장 상태에서 현재가가 매수가 위이면 미발동") {
            cycle(breakevenArmed = true).isBreakevenTriggered(10_001, buyPrice) shouldBe false
        }

        test("미무장 상태에서는 항상 미발동") {
            cycle(breakevenArmed = false).isBreakevenTriggered(9_000, buyPrice) shouldBe false
        }
    }

    context("추세 꺾임 조건 평가") {
        val prevBar = Bar(
            "000660",
            openPrice = 10_500,
            closePrice = 10_800,
            startTime = now.minusSeconds(360),
            endTime = now.minusSeconds(180)
        )
        val currentBar =
            Bar("000660", openPrice = 10_600, closePrice = 10_499, startTime = now.minusSeconds(180), endTime = now)

        test("무장 상태에서 현재봉 종가가 직전봉 시가보다 낮으면 발동") {
            cycle(trendBreakArmed = true).isTrendBreakTriggered(currentBar, prevBar) shouldBe true
        }

        test("무장 상태에서 현재봉 종가가 직전봉 시가 이상이면 미발동") {
            val strongBar = currentBar.copy(closePrice = 10_500)
            cycle(trendBreakArmed = true).isTrendBreakTriggered(strongBar, prevBar) shouldBe false
        }

        test("미무장 상태에서는 항상 미발동") {
            cycle(trendBreakArmed = false).isTrendBreakTriggered(currentBar, prevBar) shouldBe false
        }
    }

    context("분할 매도 수량 산출") {
        test("정수 절사: 50주 × 20% → 10주 + 잔여 40주") {
            cycle().splitSellQty(50) shouldBe Pair(10, 40)
        }

        test("홀수 수량 절사: 51주 × 20% → 10주 + 잔여 41주") {
            cycle().splitSellQty(51) shouldBe Pair(10, 41)
        }

        test("100% 매도 시 잔여 0") {
            TradingCycle(
                stockCode = "000660", stockName = "SK하이닉스",
                perBuyAmount = 1_000_000, buyIntervalMin = 3,
                splitSellRatio = BigDecimal("1.0"),
                midwayProfitPct = BigDecimal("3.0"),
                breakevenThresholdPct = BigDecimal("2.0"),
                stopLossPct = BigDecimal("-2.0"),
            ).splitSellQty(5) shouldBe Pair(5, 0)
        }

        test("작은 보유에서도 최소 1주 분할 매도 보장 — 3주 × 20% → 1주 + 잔여 2주") {
            // floor만 쓰면 0.6 → 0이 되어 분할 단계 비트만 소비되고 실 매도 0건이 되는 침묵 실패 방지
            cycle().splitSellQty(3) shouldBe Pair(1, 2)
        }

        test("작은 보유에서도 최소 1주 분할 매도 보장 — 4주 × 20% → 1주 + 잔여 3주") {
            cycle().splitSellQty(4) shouldBe Pair(1, 3)
        }

        test("산출 결과가 1 이상인 일반 케이스는 floor 그대로 — 5주 × 20% → 1주 + 잔여 4주") {
            cycle().splitSellQty(5) shouldBe Pair(1, 4)
        }

        test("보유 0주는 매도 0주") {
            cycle().splitSellQty(0) shouldBe Pair(0, 0)
        }

        test("1주 보유에서도 분할 매도 1주 보장 — 1주 × 20% → 1주 + 잔여 0주") {
            cycle().splitSellQty(1) shouldBe Pair(1, 0)
        }
    }

    context("보유 수량 0이면 가격 기반 시그널 평가 보류") {
        test("보유 0이면 빈 리스트 반환") {
            cycle().detectSignals(tick(9_000), holdingQty = 0, buyPrice = buyPrice).shouldBeEmpty()
        }
    }

    context("StopLoss 발동 시 다른 시그널은 모두 무시") {
        test("StopLoss와 다른 시그널이 동시 조건이면 StopLoss만 반환") {
            val result = cycle(tpStagesFired = 0b001).detectSignals(tick(9_800), holdingQty, buyPrice)
            result shouldContainExactly listOf(Signal.StopLoss)
        }

        test("StopLoss가 없으면 다른 시그널들이 정상 반환") {
            val result = cycle().detectSignals(tick(10_200), holdingQty, buyPrice)
            result.any { it is Signal.TpStage } shouldBe true
            result.none { it is Signal.StopLoss } shouldBe true
        }
    }

    context("Holding 상태 시그널 감지") {
        test("미발동 단계가 목표가 도달 시 TpStage 반환") {
            val result = cycle().detectSignals(tick(10_200), holdingQty, buyPrice)
            result shouldContainExactly listOf(Signal.TpStage(2))
        }

        test("이미 발동된 단계는 재감지하지 않음") {
            cycle(tpStagesFired = 0b001).detectSignals(tick(10_200), holdingQty, buyPrice).shouldBeEmpty()
        }

        test("Breakeven 무장 + 매수가 도달 시 Breakeven 반환") {
            cycle(breakevenArmed = true).detectSignals(tick(10_000), holdingQty, buyPrice) shouldContainExactly listOf(
                Signal.Breakeven
            )
        }

        test("TrendBreak 무장 + 봉 조건 충족 시 TrendBreak 반환") {
            val prevBar = Bar(
                "000660",
                openPrice = 10_500,
                closePrice = 10_800,
                startTime = now.minusSeconds(360),
                endTime = now.minusSeconds(180)
            )
            val currentBar = Bar(
                "000660",
                openPrice = 10_600,
                closePrice = 10_499,
                startTime = now.minusSeconds(180),
                endTime = now.plusSeconds(1)
            )
            val result = cycle(trendBreakArmed = true, tpStagesFired = 0b111)
                .detectSignals(
                    tick(10_499),
                    currentBar = currentBar,
                    prevBar = prevBar,
                    holdingQty = holdingQty,
                    buyPrice = buyPrice
                )
            result shouldContainExactly listOf(Signal.TrendBreak)
        }

        test("봉 정보 없이 호출하면 TrendBreak 평가 안 됨") {
            cycle(trendBreakArmed = true).detectSignals(tick(10_400), holdingQty, buyPrice)
                .none { it is Signal.TrendBreak } shouldBe true
        }
    }

    context("Buying 상태 시그널 감지") {
        test("매수 진행 중 손절가 이하 시 StopLoss 반환") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 2)
                .detectSignals(tick(9_800), holdingQty, buyPrice) shouldContainExactly listOf(Signal.StopLoss)
        }

        test("매수 진행 중 중도 익절 조건 충족 시 MidwayTakeProfit 반환") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 2)
                .detectSignals(tick(10_300), holdingQty, buyPrice) shouldContainExactly listOf(Signal.MidwayTakeProfit)
        }

        test("3회차 완료 후에는 MidwayTakeProfit 미발동") {
            cycle(status = TradingCycleStatus.BUYING, buyAttempt = 3)
                .detectSignals(tick(10_300), holdingQty, buyPrice).none { it is Signal.MidwayTakeProfit } shouldBe true
        }
    }

    context("Buying / Holding 외 상태에서는 가격 기반 시그널 평가 안 함") {
        test("Initiated 상태에서는 빈 리스트") {
            cycle(status = TradingCycleStatus.INITIATED).detectSignals(tick(9_000), holdingQty, buyPrice)
                .shouldBeEmpty()
        }
    }

    context("취소 요청") {
        test("Initiated/Buying/Holding 상태는 Liquidating으로 전이된다") {
            listOf(TradingCycleStatus.INITIATED, TradingCycleStatus.BUYING, TradingCycleStatus.HOLDING).forEach { from ->
                val target = cycle(status = from)
                target.requestCancel()
                target.status shouldBe TradingCycleStatus.LIQUIDATING
            }
        }

        test("이미 Liquidating이면 멱등 처리") {
            val target = cycle(status = TradingCycleStatus.LIQUIDATING)
            target.requestCancel()
            target.status shouldBe TradingCycleStatus.LIQUIDATING
        }

        test("Closed 상태에서 취소 요청하면 AlreadyClosedException") {
            val target = cycle(status = TradingCycleStatus.CLOSED)
            shouldThrow<AlreadyClosedException> { target.requestCancel() }
        }
    }

    context("매수 시작") {
        test("Initiated 상태에서 호출하면 Buying 1회차로 전이된다") {
            val target = cycle(status = TradingCycleStatus.INITIATED, buyAttempt = 0)
            target.startBuying()
            target.status shouldBe TradingCycleStatus.BUYING
            target.buyAttempt shouldBe 1
        }

        test("Initiated가 아닌 상태에서 호출하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.BUYING, buyAttempt = 1).startBuying()
            }
        }
    }

    context("매수 회차 증가") {
        test("Buying 상태에서 호출하면 회차가 1 증가한다") {
            val target = cycle(status = TradingCycleStatus.BUYING, buyAttempt = 1)
            target.incrementBuyAttempt()
            target.buyAttempt shouldBe 2
        }

        test("회차가 3이면 더 증가하지 않는다 (예외)") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.BUYING, buyAttempt = 3).incrementBuyAttempt()
            }
        }

        test("Buying이 아닌 상태에서 호출하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.HOLDING).incrementBuyAttempt()
            }
        }
    }

    context("Holding 전이") {
        test("Buying 상태에서 호출하면 Holding으로 전이된다") {
            val target = cycle(status = TradingCycleStatus.BUYING, buyAttempt = 3)
            target.transitionToHolding()
            target.status shouldBe TradingCycleStatus.HOLDING
        }

        test("Buying이 아닌 상태에서 호출하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.INITIATED).transitionToHolding()
            }
        }
    }

    context("Breakeven 무장") {
        test("Holding 상태에서 호출하면 breakevenArmed가 true로 전이") {
            val target = cycle(breakevenArmed = false)
            target.armBreakeven()
            target.breakevenArmed shouldBe true
        }

        test("Holding이 아닌 상태에서 호출하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.BUYING).armBreakeven()
            }
        }

        test("disarmBreakeven은 상태와 무관하게 false로 되돌린다") {
            val target = cycle(breakevenArmed = true)
            target.disarmBreakeven()
            target.breakevenArmed shouldBe false
        }
    }

    context("TrendBreak 무장") {
        test("Holding 상태에서 호출하면 trendBreakArmed가 true로 전이") {
            val target = cycle(trendBreakArmed = false)
            target.armTrendBreak()
            target.trendBreakArmed shouldBe true
        }

        test("Holding이 아닌 상태에서 호출하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.BUYING).armTrendBreak()
            }
        }
    }

    context("TpStage 발동 기록") {
        test("발동된 단계의 비트가 누적된다") {
            val target = cycle(tpStagesFired = 0b001)
            target.markTpStageFired(3)
            target.tpStagesFired shouldBe 0b011
            target.markTpStageFired(5)
            target.tpStagesFired shouldBe 0b111
        }

        test("동일 단계를 두 번 기록해도 비트는 변하지 않는다") {
            val target = cycle(tpStagesFired = 0b010)
            target.markTpStageFired(3)
            target.tpStagesFired shouldBe 0b010
        }

        test("Holding이 아닌 상태에서 호출하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.BUYING).markTpStageFired(2)
            }
        }
    }

    context("종료 처리") {
        val at = java.time.LocalDateTime.of(2026, 5, 5, 10, 0)

        test("Buying 상태에서 NO_FILL로 종료할 수 있다") {
            val target = cycle(status = TradingCycleStatus.BUYING, buyAttempt = 3)
            target.close(CloseReason.NO_FILL, at)
            target.status shouldBe TradingCycleStatus.CLOSED
            target.closeReason shouldBe CloseReason.NO_FILL
            target.closedAt shouldBe at
        }

        test("Liquidating 상태에서 매도 사유(TAKE_PROFIT/STOP_LOSS/BREAKEVEN/TREND_BREAK/CANCELLED)로 종료할 수 있다") {
            listOf(
                CloseReason.TAKE_PROFIT,
                CloseReason.STOP_LOSS,
                CloseReason.BREAKEVEN,
                CloseReason.TREND_BREAK,
                CloseReason.CANCELLED,
            ).forEach { reason ->
                val target = cycle(status = TradingCycleStatus.LIQUIDATING)
                target.close(reason, at)
                target.status shouldBe TradingCycleStatus.CLOSED
                target.closeReason shouldBe reason
            }
        }

        test("Buying 상태에서 보유 0일 때 CANCELLED로 직행 종료할 수 있다") {
            val target = cycle(status = TradingCycleStatus.BUYING)
            target.close(CloseReason.CANCELLED, at)
            target.status shouldBe TradingCycleStatus.CLOSED
            target.closeReason shouldBe CloseReason.CANCELLED
        }

        test("UNCLOSED는 Closed가 아닌 모든 상태에서 종료할 수 있다") {
            listOf(
                TradingCycleStatus.INITIATED,
                TradingCycleStatus.BUYING,
                TradingCycleStatus.HOLDING,
                TradingCycleStatus.LIQUIDATING,
            ).forEach { from ->
                val target = cycle(status = from)
                target.close(CloseReason.UNCLOSED, at)
                target.status shouldBe TradingCycleStatus.CLOSED
                target.closeReason shouldBe CloseReason.UNCLOSED
            }
        }

        test("Holding 상태에서 매도 사유로 직접 종료하려 하면 예외 (Liquidating 경유 필요)") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.HOLDING).close(CloseReason.TAKE_PROFIT, at)
            }
        }

        test("Buying 상태에서 매도 사유로 종료하려 하면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(status = TradingCycleStatus.BUYING).close(CloseReason.STOP_LOSS, at)
            }
        }

        test("이미 Closed인 사이클을 다시 종료하면 AlreadyClosedException") {
            val target = cycle(status = TradingCycleStatus.LIQUIDATING)
            target.close(CloseReason.STOP_LOSS, at)
            shouldThrow<AlreadyClosedException> { target.close(CloseReason.UNCLOSED, at) }
        }
    }
})
