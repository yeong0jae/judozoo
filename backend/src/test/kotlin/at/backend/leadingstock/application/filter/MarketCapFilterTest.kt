package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MarketCapFilterTest : FunSpec({
    val filter = MarketCapFilter(defaultCriteria())   // minMarketCap = 3000 (억원)

    context("시가총액 임계값 경계") {
        test("기준선 동일하면 통과") {
            filter.filter(snapshot(marketCap = 3000)) shouldBe true
        }
        test("기준선보다 크면 통과") {
            filter.filter(snapshot(marketCap = 5000)) shouldBe true
        }
        test("기준선 미달이면 차단") {
            filter.filter(snapshot(marketCap = 2999)) shouldBe false
        }
        test("시가총액 0이면 차단") {
            filter.filter(snapshot(marketCap = 0)) shouldBe false
        }
    }
})
