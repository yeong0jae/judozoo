package at.backend.trading.domain.cycle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class TradingCycleTransitionTest : FunSpec({

    fun cycle(
        status: TradingCycleStatus,
        buyAttempt: Int = 0,
    ) = TradingCycle(
        accountNo = "00000000",
        stockCode = "000660",
        stockName = "SK하이닉스",
        perBuyAmount = 1_000_000,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.20"),
        midwayProfitPct = BigDecimal("3.0"),
        breakevenThresholdPct = BigDecimal("2.0"),
        stopLossPct = BigDecimal("-2.0"),
        status = status,
        buyAttempt = buyAttempt,
    )

    context("Initiated 상태 전이") {
        val initiated = cycle(TradingCycleStatus.INITIATED)

        test("Buying(1)으로 전이 허용") {
            initiated.canTransitionTo(TradingCycleStatus.BUYING, nextBuyAttempt = 1) shouldBe true
        }

        test("Buying(2)로 직접 전이 불허 — 1차부터 시작해야 함") {
            initiated.canTransitionTo(TradingCycleStatus.BUYING, nextBuyAttempt = 2) shouldBe false
        }

        test("Holding으로 직접 전이 불허") {
            initiated.canTransitionTo(TradingCycleStatus.HOLDING) shouldBe false
        }

        test("Closed로 직접 전이 불허") {
            initiated.canTransitionTo(TradingCycleStatus.CLOSED, nextCloseReason = CloseReason.NO_FILL) shouldBe false
        }
    }

    context("Buying 상태 전이") {
        test("다음 회차(+1)로 전이 허용") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 1).canTransitionTo(
                TradingCycleStatus.BUYING,
                nextBuyAttempt = 2
            ) shouldBe true
            cycle(TradingCycleStatus.BUYING, buyAttempt = 2).canTransitionTo(
                TradingCycleStatus.BUYING,
                nextBuyAttempt = 3
            ) shouldBe true
        }

        test("회차 건너뜀 불허") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 1).canTransitionTo(
                TradingCycleStatus.BUYING,
                nextBuyAttempt = 3
            ) shouldBe false
        }

        test("Holding으로 전이 허용 — 3회 완료 또는 중도 익절") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 3).canTransitionTo(TradingCycleStatus.HOLDING) shouldBe true
            cycle(TradingCycleStatus.BUYING, buyAttempt = 2).canTransitionTo(TradingCycleStatus.HOLDING) shouldBe true
        }

        test("Liquidating으로 전이 허용 — 손절/취소") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 1).canTransitionTo(TradingCycleStatus.LIQUIDATING) shouldBe true
        }

        test("Closed(NO_FILL)로 직행 허용 — 보유=0") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 3).canTransitionTo(
                TradingCycleStatus.CLOSED,
                nextCloseReason = CloseReason.NO_FILL
            ) shouldBe true
        }

        test("Closed(CANCELLED)로 직행 허용 — 보유=0 취소") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 2).canTransitionTo(
                TradingCycleStatus.CLOSED,
                nextCloseReason = CloseReason.CANCELLED
            ) shouldBe true
        }

        test("Closed(TAKE_PROFIT)로 직행 불허") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 3).canTransitionTo(
                TradingCycleStatus.CLOSED,
                nextCloseReason = CloseReason.TAKE_PROFIT
            ) shouldBe false
        }

        test("Initiated로 역전이 불허") {
            cycle(TradingCycleStatus.BUYING, buyAttempt = 1).canTransitionTo(TradingCycleStatus.INITIATED) shouldBe false
        }
    }

    context("Holding 상태 전이") {
        val holding = cycle(TradingCycleStatus.HOLDING)

        test("자기 자신(Holding)으로 전이 허용 — TpStage 부분 매도 후 잔여 유지") {
            holding.canTransitionTo(TradingCycleStatus.HOLDING) shouldBe true
        }

        test("Liquidating으로 전이 허용 — BE/TB/LU/MC") {
            holding.canTransitionTo(TradingCycleStatus.LIQUIDATING) shouldBe true
        }

        test("Closed로 직접 전이 불허") {
            holding.canTransitionTo(TradingCycleStatus.CLOSED, nextCloseReason = CloseReason.TAKE_PROFIT) shouldBe false
        }

        test("Buying으로 역전이 불허") {
            holding.canTransitionTo(TradingCycleStatus.BUYING, nextBuyAttempt = 1) shouldBe false
        }
    }

    context("Liquidating 상태 전이") {
        val liquidating = cycle(TradingCycleStatus.LIQUIDATING)

        test("Closed로 전이 허용") {
            liquidating.canTransitionTo(TradingCycleStatus.CLOSED) shouldBe true
        }

        test("Holding으로 역전이 불허") {
            liquidating.canTransitionTo(TradingCycleStatus.HOLDING) shouldBe false
        }
    }

    context("Closed 상태 전이") {
        val closed = cycle(TradingCycleStatus.CLOSED)

        test("어떤 상태로도 전이 불허 — 종료 상태") {
            closed.canTransitionTo(TradingCycleStatus.INITIATED) shouldBe false
            closed.canTransitionTo(TradingCycleStatus.BUYING, nextBuyAttempt = 1) shouldBe false
            closed.canTransitionTo(TradingCycleStatus.HOLDING) shouldBe false
            closed.canTransitionTo(TradingCycleStatus.CLOSED) shouldBe false
        }
    }

    context("불변식") {
        test("손절 비율이 0 이상이면 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle(TradingCycleStatus.INITIATED).copy(stopLossPct = BigDecimal("0.0"))
            }
        }
    }
})

private fun TradingCycle.copy(
    stopLossPct: BigDecimal = this.stopLossPct,
) = TradingCycle(
    accountNo = accountNo,
    stockCode = stockCode,
    stockName = stockName,
    perBuyAmount = perBuyAmount,
    buyIntervalMin = buyIntervalMin,
    splitSellRatio = splitSellRatio,
    midwayProfitPct = midwayProfitPct,
    breakevenThresholdPct = breakevenThresholdPct,
    stopLossPct = stopLossPct,
    status = status,
    buyAttempt = buyAttempt,
)
