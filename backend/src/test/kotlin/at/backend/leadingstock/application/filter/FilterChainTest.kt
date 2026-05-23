package at.backend.leadingstock.application.filter

import at.backend.leadingstock.domain.LeadingStockSnapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FilterChainTest : FunSpec({

    fun acceptAll(): StockFilter = object : StockFilter {
        override val name = "all-pass"
        override fun filter(stock: LeadingStockSnapshot) = true
        override fun evaluate(stock: LeadingStockSnapshot) =
            FilterEvaluationResult(name, "전부 통과", "ok", true)
    }

    fun rejectAll(): StockFilter = object : StockFilter {
        override val name = "all-reject"
        override fun filter(stock: LeadingStockSnapshot) = false
        override fun evaluate(stock: LeadingStockSnapshot) =
            FilterEvaluationResult(name, "전부 차단", "no", false)
    }

    fun acceptByCode(vararg codes: String): StockFilter = object : StockFilter {
        override val name = "code-allowlist"
        override fun filter(stock: LeadingStockSnapshot) = stock.stockCode in codes
        override fun evaluate(stock: LeadingStockSnapshot) =
            FilterEvaluationResult(name, codes.joinToString(), stock.stockCode, filter(stock))
    }

    val a = snapshot(stockCode = "A")
    val b = snapshot(stockCode = "B")
    val c = snapshot(stockCode = "C")

    context("빈 input") {
        test("입력이 비어있으면 빈 결과") {
            val chain = FilterChain(listOf(acceptAll()))
            chain.apply(emptyList()).shouldBeEmpty()
        }
    }

    context("모든 필터 통과") {
        test("필터 전부 통과면 input 그대로 반환") {
            val chain = FilterChain(listOf(acceptAll(), acceptAll()))
            chain.apply(listOf(a, b, c)) shouldContainExactly listOf(a, b, c)
        }
    }

    context("순차 누적 차감") {
        test("이전 필터가 떨군 종목은 다음 필터에 안 넘어감") {
            val chain = FilterChain(
                listOf(
                    acceptByCode("A", "B"),     // A, B 통과
                    acceptByCode("B", "C"),     // B만 통과 (C는 이미 떨어짐)
                ),
            )
            chain.apply(listOf(a, b, c)) shouldContainExactly listOf(b)
        }
    }

    context("중간에 0이 되면 early break") {
        test("중간 필터가 전부 떨어뜨리면 그 이후 필터는 실행되어도 빈 리스트 유지") {
            var afterRejectCalled = false
            val afterReject = object : StockFilter {
                override val name = "after-reject"
                override fun filter(stock: LeadingStockSnapshot): Boolean {
                    afterRejectCalled = true
                    return true
                }
                override fun evaluate(stock: LeadingStockSnapshot) =
                    FilterEvaluationResult(name, "noop", "noop", true)
            }
            val chain = FilterChain(listOf(acceptAll(), rejectAll(), afterReject))
            chain.apply(listOf(a, b, c)).shouldBeEmpty()
            afterRejectCalled shouldBe false   // 빈 리스트가 되면 다음 필터 호출 안 함
        }
    }

    context("필터가 0개") {
        test("필터 없으면 input 그대로 반환") {
            val chain = FilterChain(emptyList())
            chain.apply(listOf(a, b)) shouldContainExactly listOf(a, b)
        }
    }
})
