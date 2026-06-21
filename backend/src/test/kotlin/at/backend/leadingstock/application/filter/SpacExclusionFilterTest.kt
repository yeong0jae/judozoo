package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpacExclusionFilterTest : FunSpec({
    val filter = SpacExclusionFilter()

    context("개별 종목 통과") {
        test("일반 종목명은 통과") {
            filter.filter(snapshot(stockName = "삼성전자")) shouldBe true
        }
    }

    context("스팩 차단") {
        test("종목명에 '스팩'이 들어가면 차단") {
            filter.filter(snapshot(stockName = "메리츠제2호스팩")) shouldBe false
        }
        test("운용사 호수 스팩도 차단") {
            filter.filter(snapshot(stockName = "교보15호스팩")) shouldBe false
        }
    }
})
