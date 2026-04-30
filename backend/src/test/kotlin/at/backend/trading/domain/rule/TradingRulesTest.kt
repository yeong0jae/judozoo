package at.backend.trading.domain.rule

import at.backend.trading.domain.Bar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class TradingRulesTest : FunSpec({

    context("calcBuyPrice — 매수가 산정") {
        test("단일 체결: 원가평균 × (1 + sellCostRate) 올림") {
            val executions = listOf(Execution(executedPrice = 10_000, executedQty = 10, fee = 150))
            // 원가평균 = (10000×10 + 150) / 10 = 10015.0
            // 매수가 = ceil(10015.0 × 1.0025) = ceil(10040.04) = 10041
            TradingRules.calcBuyPrice(executions, sellCostRate = 0.0025) shouldBe 10_041
        }

        test("복수 체결: 가중평균 + 수수료 합산") {
            val executions = listOf(
                Execution(executedPrice = 10_000, executedQty = 5, fee = 75),
                Execution(executedPrice = 10_500, executedQty = 5, fee = 79),
            )
            // 원가합계 = (10000×5 + 75) + (10500×5 + 79) = 50075 + 52579 = 102654
            // 총수량 = 10
            // 원가평균 = 10265.4
            // 매수가 = ceil(10265.4 × 1.0025) = ceil(10291.0) = 10292
            TradingRules.calcBuyPrice(executions, sellCostRate = 0.0025) shouldBe 10_292
        }

        test("빈 체결 목록은 예외") {
            shouldThrow<IllegalArgumentException> {
                TradingRules.calcBuyPrice(emptyList(), sellCostRate = 0.0025)
            }
        }
    }

    context("calcSplitSellQty — 분할 매도 수량") {
        test("정수 절사: 50주 × 20% = 10주, 잔여 40주") {
            TradingRules.calcSplitSellQty(holdingQty = 50, splitSellRatio = 0.20) shouldBe Pair(10, 40)
        }

        test("홀수 수량 절사: 51주 × 20% = 10주(절사), 잔여 41주") {
            TradingRules.calcSplitSellQty(holdingQty = 51, splitSellRatio = 0.20) shouldBe Pair(10, 41)
        }

        test("잔여가 0이 되는 케이스: 5주 × 100% = 5주, 잔여 0") {
            TradingRules.calcSplitSellQty(holdingQty = 5, splitSellRatio = 1.0) shouldBe Pair(5, 0)
        }
    }

    context("isStopLossTriggered — 손절 트리거") {
        val buyPrice = 10_000
        val stopLossPct = -0.02  // -2%

        test("현재가 ≤ 손절가(9800) 이면 발동") {
            TradingRules.isStopLossTriggered(currentPrice = 9_800, buyPrice, stopLossPct) shouldBe true
            TradingRules.isStopLossTriggered(currentPrice = 9_799, buyPrice, stopLossPct) shouldBe true
        }

        test("현재가 > 손절가(9800) 이면 미발동") {
            TradingRules.isStopLossTriggered(currentPrice = 9_801, buyPrice, stopLossPct) shouldBe false
        }
    }

    context("isMidwayTakeProfitTriggered — 중도 익절 트리거") {
        val buyPrice = 10_000
        val midwayProfitPct = 3.0  // +3%

        test("현재가 ≥ 목표가(10300) 이면 발동") {
            TradingRules.isMidwayTakeProfitTriggered(currentPrice = 10_300, buyPrice, midwayProfitPct) shouldBe true
            TradingRules.isMidwayTakeProfitTriggered(currentPrice = 10_500, buyPrice, midwayProfitPct) shouldBe true
        }

        test("현재가 < 목표가(10300) 이면 미발동") {
            TradingRules.isMidwayTakeProfitTriggered(currentPrice = 10_299, buyPrice, midwayProfitPct) shouldBe false
        }
    }

    context("isTpStageTriggered — 단계 익절 트리거") {
        val buyPrice = 10_000

        test("2% 단계: 미발동 상태에서 현재가 ≥ 10200 이면 발동") {
            TradingRules.isTpStageTriggered(10_200, buyPrice, stagePct = 2, tpStagesFired = 0b000) shouldBe true
        }

        test("2% 단계: 이미 발동된 비트면 미발동") {
            TradingRules.isTpStageTriggered(10_200, buyPrice, stagePct = 2, tpStagesFired = 0b001) shouldBe false
        }

        test("5% 단계: 미발동 상태에서 현재가 ≥ 10500 이면 발동") {
            TradingRules.isTpStageTriggered(10_500, buyPrice, stagePct = 5, tpStagesFired = 0b000) shouldBe true
        }

        test("3% 단계만 발동 안 된 상태에서 현재가 10350: 3% 발동") {
            TradingRules.isTpStageTriggered(10_350, buyPrice, stagePct = 3, tpStagesFired = 0b001) shouldBe true
        }

        test("현재가가 기준 미달이면 미발동") {
            TradingRules.isTpStageTriggered(10_199, buyPrice, stagePct = 2, tpStagesFired = 0b000) shouldBe false
        }
    }

    context("isBreakevenTriggered — 본전 매도 트리거") {
        val buyPrice = 10_000

        test("무장 상태에서 현재가 ≤ 매수가이면 발동") {
            TradingRules.isBreakevenTriggered(currentPrice = 10_000, buyPrice, armed = true) shouldBe true
            TradingRules.isBreakevenTriggered(currentPrice = 9_999, buyPrice, armed = true) shouldBe true
        }

        test("무장 상태에서 현재가 > 매수가이면 미발동") {
            TradingRules.isBreakevenTriggered(currentPrice = 10_001, buyPrice, armed = true) shouldBe false
        }

        test("미무장 상태에서는 항상 미발동") {
            TradingRules.isBreakevenTriggered(currentPrice = 9_000, buyPrice, armed = false) shouldBe false
        }
    }

    context("isTrendBreakTriggered — 추세 꺾임 트리거") {
        val now = Instant.now()
        val prevBar = Bar("000660", openPrice = 10_500, closePrice = 10_800, startTime = now.minusSeconds(360), endTime = now.minusSeconds(180))
        val currentBar = Bar("000660", openPrice = 10_600, closePrice = 10_499, startTime = now.minusSeconds(180), endTime = now)

        test("무장 상태에서 현재봉 종가 < 직전봉 시가이면 발동") {
            // 현재봉 종가 10499 < 직전봉 시가 10500
            TradingRules.isTrendBreakTriggered(currentBar, prevBar, armed = true) shouldBe true
        }

        test("무장 상태에서 현재봉 종가 ≥ 직전봉 시가이면 미발동") {
            val strongBar = currentBar.copy(closePrice = 10_500)
            TradingRules.isTrendBreakTriggered(strongBar, prevBar, armed = true) shouldBe false
        }

        test("미무장 상태에서는 항상 미발동") {
            TradingRules.isTrendBreakTriggered(currentBar, prevBar, armed = false) shouldBe false
        }
    }
})
