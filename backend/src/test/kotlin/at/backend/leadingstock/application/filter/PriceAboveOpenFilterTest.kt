package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PriceAboveOpenFilterTest : FunSpec({
    val filter = PriceAboveOpenFilter()

    context("현재가와 시가 비교") {
        test("현재가 == 시가면 통과 (boundary)") {
            filter.filter(snapshot(currentPrice = 70_000, openingPrice = 70_000)) shouldBe true
        }
        test("현재가 > 시가면 통과") {
            filter.filter(snapshot(currentPrice = 71_000, openingPrice = 70_000)) shouldBe true
        }
        test("현재가 < 시가면 차단") {
            filter.filter(snapshot(currentPrice = 69_500, openingPrice = 70_000)) shouldBe false
        }
    }

    context("시가 0 처리") {
        test("시가가 0이면 데이터 없음으로 차단") {
            filter.filter(snapshot(currentPrice = 70_000, openingPrice = 0)) shouldBe false
        }
    }
})
