package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DailyHighPositionFilterTest : FunSpec({
    // maxHighPositionDropRate = -5.0 → 60봉 고가 대비 -5% 이상이어야 통과 (-3%는 통과, -10%는 차단)

    context("고가 대비 현재가 하락률") {
        test("고가 대비 -3% 면 통과 (임계값 -5%보다 덜 하락)") {
            val filter = DailyHighPositionFilter(defaultCriteria()) { _ ->
                listOf(dailyCandle(highPrice = 100_000))
            }
            filter.filter(snapshot(currentPrice = 97_000)) shouldBe true
        }
        test("정확히 임계값(-5%)이면 통과") {
            val filter = DailyHighPositionFilter(defaultCriteria()) { _ ->
                listOf(dailyCandle(highPrice = 100_000))
            }
            filter.filter(snapshot(currentPrice = 95_000)) shouldBe true
        }
        test("-10% 하락이면 차단") {
            val filter = DailyHighPositionFilter(defaultCriteria()) { _ ->
                listOf(dailyCandle(highPrice = 100_000))
            }
            filter.filter(snapshot(currentPrice = 90_000)) shouldBe false
        }
    }

    context("데이터 부족 처리") {
        test("일봉 0개면 차단") {
            val filter = DailyHighPositionFilter(defaultCriteria()) { _ -> emptyList() }
            filter.filter(snapshot()) shouldBe false
        }
        test("모든 일봉 고가가 0이면 차단") {
            val filter = DailyHighPositionFilter(defaultCriteria()) { _ ->
                listOf(dailyCandle(highPrice = 0))
            }
            filter.filter(snapshot()) shouldBe false
        }
    }

    context("여러 일봉 중 최대 고가 사용") {
        test("60봉 중 가장 높은 고가를 기준으로 비교") {
            val filter = DailyHighPositionFilter(defaultCriteria()) { _ ->
                listOf(
                    dailyCandle(highPrice = 80_000),
                    dailyCandle(highPrice = 100_000),
                    dailyCandle(highPrice = 90_000),
                )
            }
            // 기준 = 100_000 → -3% = 97_000
            filter.filter(snapshot(currentPrice = 97_000)) shouldBe true
            filter.filter(snapshot(currentPrice = 90_000)) shouldBe false
        }
    }
})
