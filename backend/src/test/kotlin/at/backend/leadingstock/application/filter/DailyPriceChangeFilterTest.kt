package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DailyPriceChangeFilterTest : FunSpec({
    val filter = DailyPriceChangeFilter(defaultCriteria())   // minDailyPriceChangeRate = 5.0

    context("당일 등락률 임계값 경계") {
        test("정확히 임계값(5.0%)이면 통과") {
            filter.filter(snapshot(priceChangeRate = 5.0)) shouldBe true
        }
        test("임계값 초과하면 통과") {
            filter.filter(snapshot(priceChangeRate = 10.0)) shouldBe true
        }
        test("임계값 미달이면 차단") {
            filter.filter(snapshot(priceChangeRate = 4.9)) shouldBe false
        }
        test("음수(하락)면 차단") {
            filter.filter(snapshot(priceChangeRate = -3.0)) shouldBe false
        }
    }
})
