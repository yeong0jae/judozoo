package at.backend.trading.domain.execution

import at.backend.trading.domain.cycle.TradingCycle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ExecutionTest : FunSpec({

    fun cycle() = TradingCycle(
        stockCode = "000660",
        stockName = "SK하이닉스",
        perBuyAmount = 1_000_000,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.20"),
        midwayProfitPct = BigDecimal("3.0"),
        breakevenThresholdPct = BigDecimal("2.0"),
        stopLossPct = BigDecimal("-2.0"),
    )

    fun execution(executedPrice: Int, executedQty: Int, fee: Int, tax: Int = 0) =
        Execution(orderId = 1L, executedQty = executedQty, executedPrice = executedPrice, fee = fee, tax = tax)

    context("매수가 산정") {
        test("단일 체결: 원가평균에 매도 비용률 반영") {
            val executions = listOf(execution(executedPrice = 10_000, executedQty = 10, fee = 150))
            // 원가평균 = (10000×10 + 150) / 10 = 10015.0
            // 매수가 = ceil(10015.0 × 1.0025) = 10041
            cycle().calculateBuyPrice(executions, sellCostRate = 0.0025) shouldBe 10_041
        }

        test("복수 체결: 가중평균과 수수료 합산") {
            val executions = listOf(
                execution(executedPrice = 10_000, executedQty = 5, fee = 75),
                execution(executedPrice = 10_500, executedQty = 5, fee = 79),
            )
            // 원가합계 = 50075 + 52579 = 102654, 총수량 10 → 평균 10265.4
            // 매수가 = ceil(10265.4 × 1.0025) = 10292
            cycle().calculateBuyPrice(executions, sellCostRate = 0.0025) shouldBe 10_292
        }

        test("빈 체결 목록은 예외") {
            shouldThrow<IllegalArgumentException> {
                cycle().calculateBuyPrice(emptyList(), sellCostRate = 0.0025)
            }
        }
    }

    context("Execution 불변식") {
        test("체결가가 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> {
                execution(executedPrice = 0, executedQty = 10, fee = 100)
            }
        }

        test("수량이 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> {
                execution(executedPrice = 10_000, executedQty = 0, fee = 100)
            }
        }

        test("수수료가 음수이면 예외") {
            shouldThrow<IllegalArgumentException> {
                execution(executedPrice = 10_000, executedQty = 10, fee = -1)
            }
        }

        test("세금이 음수이면 예외") {
            shouldThrow<IllegalArgumentException> {
                execution(executedPrice = 10_000, executedQty = 10, fee = 0, tax = -1)
            }
        }
    }
})
