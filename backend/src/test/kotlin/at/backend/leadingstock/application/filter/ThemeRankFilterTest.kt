package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ThemeRankFilterTest : FunSpec({
    // maxThemeRank = 5 → 테마 상위 5위 이내이거나, 테마가 아예 없으면 통과

    context("테마 순위 임계값 경계") {
        test("1위는 통과") {
            val filter = ThemeRankFilter(defaultCriteria()) { _ -> 1 }
            filter.filter(snapshot()) shouldBe true
        }
        test("정확히 5위는 통과 (boundary)") {
            val filter = ThemeRankFilter(defaultCriteria()) { _ -> 5 }
            filter.filter(snapshot()) shouldBe true
        }
        test("6위는 차단") {
            val filter = ThemeRankFilter(defaultCriteria()) { _ -> 6 }
            filter.filter(snapshot()) shouldBe false
        }
    }

    context("테마 미존재 처리") {
        test("rank null이면 통과 — 테마 없는 종목은 노이즈 배제 대상 아님") {
            val filter = ThemeRankFilter(defaultCriteria()) { _ -> null }
            filter.filter(snapshot()) shouldBe true
        }
    }
})
