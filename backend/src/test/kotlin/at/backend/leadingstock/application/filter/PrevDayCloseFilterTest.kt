package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PrevDayCloseFilterTest : FunSpec({
    // maxPrevCloseChangeRate = 25.0 → 어제(candles[1])의 changeRate 25% 이하면 통과
    // "어제가 너무 많이 오른 종목은 이미 늦었다"는 정성적 기준

    context("어제 등락률 임계값 경계") {
        test("어제 등락률 +10%는 통과") {
            val filter = PrevDayCloseFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(changeRate = 5.0),     // 오늘
                    dailyCandle(changeRate = 10.0),    // 어제
                )
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("어제 정확히 +25%면 통과 (boundary)") {
            val filter = PrevDayCloseFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(changeRate = 0.0),
                    dailyCandle(changeRate = 25.0),
                )
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("어제 +30%면 차단 (이미 늦었다)") {
            val filter = PrevDayCloseFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(changeRate = 0.0),
                    dailyCandle(changeRate = 30.0),
                )
            }
            filter.filter(snapshot()) shouldBe false
        }
        test("어제가 하락(-10%)이면 통과") {
            val filter = PrevDayCloseFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(changeRate = 5.0),
                    dailyCandle(changeRate = -10.0),
                )
            }
            filter.filter(snapshot()) shouldBe true
        }
    }

    context("데이터 부족 처리") {
        test("일봉이 1개뿐이면 차단") {
            val filter = PrevDayCloseFilter(defaultCriteria()) { _ -> listOf(dailyCandle()) }
            filter.filter(snapshot()) shouldBe false
        }
        test("일봉 0개면 차단") {
            val filter = PrevDayCloseFilter(defaultCriteria()) { _ -> emptyList() }
            filter.filter(snapshot()) shouldBe false
        }
    }
})
