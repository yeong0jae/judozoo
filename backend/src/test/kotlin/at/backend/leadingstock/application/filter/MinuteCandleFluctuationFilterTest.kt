package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MinuteCandleFluctuationFilterTest : FunSpec({
    // maxMinuteFluctuationRate = 4.0 → 최신 1분봉 등락률 절대값 4% 이하면 통과

    context("최신 1분봉 등락률 절대값") {
        test("상승 +2%는 통과") {
            val filter = MinuteCandleFluctuationFilter(defaultCriteria()) { _ ->
                listOf(minuteCandle(openPrice = 70_000, closePrice = 71_400))
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("하락 -2%도 통과 (절대값 기준)") {
            val filter = MinuteCandleFluctuationFilter(defaultCriteria()) { _ ->
                listOf(minuteCandle(openPrice = 70_000, closePrice = 68_600))
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("정확히 4% 등락이면 통과 (boundary)") {
            val filter = MinuteCandleFluctuationFilter(defaultCriteria()) { _ ->
                listOf(minuteCandle(openPrice = 70_000, closePrice = 72_800))
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("5% 급등이면 차단") {
            val filter = MinuteCandleFluctuationFilter(defaultCriteria()) { _ ->
                listOf(minuteCandle(openPrice = 70_000, closePrice = 73_500))
            }
            filter.filter(snapshot()) shouldBe false
        }
    }

    context("데이터 부족 처리") {
        test("분봉 0개면 차단") {
            val filter = MinuteCandleFluctuationFilter(defaultCriteria()) { _ -> emptyList() }
            filter.filter(snapshot()) shouldBe false
        }
        test("시가가 0이면 차단") {
            val filter = MinuteCandleFluctuationFilter(defaultCriteria()) { _ ->
                listOf(minuteCandle(openPrice = 0, closePrice = 70_000))
            }
            filter.filter(snapshot()) shouldBe false
        }
    }
})
