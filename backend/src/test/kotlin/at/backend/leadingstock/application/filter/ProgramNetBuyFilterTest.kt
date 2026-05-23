package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProgramNetBuyFilterTest : FunSpec({
    // minProgramNetBuy = -10_000 (백만원) → 프로그램 순매수가 -100억원 이상이어야 통과

    context("프로그램 순매수 임계값 경계 (백만원 단위)") {
        test("순매수 +5000 (백만원)이면 통과") {
            val filter = ProgramNetBuyFilter(defaultCriteria()) { _ -> 5_000L }
            filter.filter(snapshot()) shouldBe true
        }
        test("순매수 정확히 임계값(-10_000)이면 통과") {
            val filter = ProgramNetBuyFilter(defaultCriteria()) { _ -> -10_000L }
            filter.filter(snapshot()) shouldBe true
        }
        test("순매수 -10_001이면 차단 (임계값 미달)") {
            val filter = ProgramNetBuyFilter(defaultCriteria()) { _ -> -10_001L }
            filter.filter(snapshot()) shouldBe false
        }
        test("순매수 0이면 통과") {
            val filter = ProgramNetBuyFilter(defaultCriteria()) { _ -> 0L }
            filter.filter(snapshot()) shouldBe true
        }
    }
})
