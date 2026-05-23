package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TradingValueRankFilterTest : FunSpec({
    val filter = TradingValueRankFilter(defaultCriteria())   // maxTradingValueRank = 30

    context("거래대금 순위 임계값 경계") {
        test("1위는 통과") {
            filter.filter(snapshot(tradingValueRank = 1)) shouldBe true
        }
        test("30위는 통과 (경계 포함)") {
            filter.filter(snapshot(tradingValueRank = 30)) shouldBe true
        }
        test("31위는 차단") {
            filter.filter(snapshot(tradingValueRank = 31)) shouldBe false
        }
    }
})
