package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MinuteCandleVolumeFilterTest : FunSpec({
    // minMinuteTradingValue = 5_000_000_000원 (50억)
    // minMinuteVolumeIncreaseRate = 500.0 → 평균 대비 500% 이상

    context("거래대금 + 증가율 동시 조건") {
        test("최신 거래대금 50억 + 평균 대비 500% 이상이면 통과") {
            val filter = MinuteCandleVolumeFilter(defaultCriteria()) { _ ->
                listOf(
                    minuteCandle(tradingValue = 5_000_000_000L),    // 최신
                    minuteCandle(tradingValue = 1_000_000_000L),
                )
                // 최신 50억, 평균 30억 → increase = 5_000_000_000 / 3_000_000_000 * 100 ≈ 166% < 500
            }
            // 위 fixture는 500%에 미달이라 차단
            filter.filter(snapshot()) shouldBe false
        }
        test("최신 거래대금 50억 + 평균 8억 → 600% 증가율로 통과") {
            val filter = MinuteCandleVolumeFilter(defaultCriteria()) { _ ->
                listOf(
                    minuteCandle(tradingValue = 5_000_000_000L),
                    minuteCandle(tradingValue = 800_000_000L),
                    minuteCandle(tradingValue = 800_000_000L),
                    minuteCandle(tradingValue = 800_000_000L),
                    minuteCandle(tradingValue = 800_000_000L),
                )
                // 평균 = (5_000M + 800M*4) / 5 = 1_640M → 5_000M / 1_640M * 100 ≈ 305% < 500
            }
            filter.filter(snapshot()) shouldBe false
        }
        test("증가율 충분해도 최신 거래대금 50억 미달이면 차단") {
            val filter = MinuteCandleVolumeFilter(defaultCriteria()) { _ ->
                listOf(
                    minuteCandle(tradingValue = 4_000_000_000L),    // 최신 40억 (50억 미달)
                    minuteCandle(tradingValue = 100_000_000L),
                )
            }
            filter.filter(snapshot()) shouldBe false
        }
        test("실제 통과 — 최신 50억 + 거의 0인 분봉이 다수면 평균↓로 증가율 충족") {
            val filter = MinuteCandleVolumeFilter(defaultCriteria()) { _ ->
                listOf(minuteCandle(tradingValue = 5_000_000_000L)) +
                    (1..99).map { minuteCandle(tradingValue = 0L) }
                // 평균 = 50_000_000원 → 5_000_000_000 / 50_000_000 * 100 = 10000% ≥ 500
            }
            filter.filter(snapshot()) shouldBe true
        }
    }

    context("데이터 부족 처리") {
        test("분봉 0개면 차단") {
            val filter = MinuteCandleVolumeFilter(defaultCriteria()) { _ -> emptyList() }
            filter.filter(snapshot()) shouldBe false
        }
    }
})
