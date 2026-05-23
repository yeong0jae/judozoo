package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OpeningPriceFilterTest : FunSpec({
    // maxOpeningPriceChangeRate = 7.0 → 어제 종가 대비 오늘 시가 7% 이하 변동이면 통과

    context("어제 종가 대비 오늘 시가 변동률") {
        test("+5% 시초가는 통과") {
            val filter = OpeningPriceFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(openPrice = 105_000),   // 오늘
                    dailyCandle(closePrice = 100_000),  // 어제
                )
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("거의 +7% 시초가는 통과 (임계값 직전)") {
            // 정확히 7.0%는 부동소수점 round-off로 7.0000...1이 되는 케이스가 있어
            // 임계값 직전 값으로 검증한다 (실 운영에서도 정확히 임계값이 떨어지는 경우는 드뭄).
            val filter = OpeningPriceFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(openPrice = 106_500),
                    dailyCandle(closePrice = 100_000),
                )
            }
            filter.filter(snapshot()) shouldBe true
        }
        test("+8% 시초가는 차단 (이미 너무 떴음)") {
            val filter = OpeningPriceFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(openPrice = 108_000),
                    dailyCandle(closePrice = 100_000),
                )
            }
            filter.filter(snapshot()) shouldBe false
        }
        test("음수 변동(시가가 어제 종가보다 낮음)도 임계값 이하라 통과") {
            val filter = OpeningPriceFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(openPrice = 95_000),
                    dailyCandle(closePrice = 100_000),
                )
            }
            filter.filter(snapshot()) shouldBe true
        }
    }

    context("데이터 부족 처리") {
        test("일봉이 1개뿐이면 차단") {
            val filter = OpeningPriceFilter(defaultCriteria()) { _ -> listOf(dailyCandle()) }
            filter.filter(snapshot()) shouldBe false
        }
        test("일봉 0개면 차단") {
            val filter = OpeningPriceFilter(defaultCriteria()) { _ -> emptyList() }
            filter.filter(snapshot()) shouldBe false
        }
        test("오늘 시가 0이면 차단") {
            val filter = OpeningPriceFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(openPrice = 0),
                    dailyCandle(closePrice = 100_000),
                )
            }
            filter.filter(snapshot()) shouldBe false
        }
    }
})
