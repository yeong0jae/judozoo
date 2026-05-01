package at.backend.trading.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExecutionsTest : FunSpec({

    context("매수가 산정") {
        test("단일 체결: 원가평균에 매도 비용률 반영") {
            val executions = Executions(listOf(Execution(executedPrice = 10_000, executedQty = 10, fee = 150)))
            // 원가평균 = (10000×10 + 150) / 10 = 10015.0
            // 매수가 = ceil(10015.0 × 1.0025) = 10041
            executions.calculateBuyPrice(sellCostRate = 0.0025) shouldBe 10_041
        }

        test("복수 체결: 가중평균과 수수료 합산") {
            val executions = Executions(
                listOf(
                    Execution(executedPrice = 10_000, executedQty = 5, fee = 75),
                    Execution(executedPrice = 10_500, executedQty = 5, fee = 79),
                )
            )
            // 원가합계 = 50075 + 52579 = 102654, 총수량 10 → 평균 10265.4
            // 매수가 = ceil(10265.4 × 1.0025) = 10292
            executions.calculateBuyPrice(sellCostRate = 0.0025) shouldBe 10_292
        }
    }

    context("불변식") {
        test("빈 체결 목록은 생성 시 예외") {
            shouldThrow<IllegalArgumentException> {
                Executions(emptyList())
            }
        }
    }
})
