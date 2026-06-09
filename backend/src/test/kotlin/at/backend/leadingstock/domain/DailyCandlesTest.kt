package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class DailyCandlesTest : FunSpec({

    val today = LocalDate.of(2026, 6, 9)

    fun candle(date: LocalDate, volume: Long) =
        DailyCandle(
            date = date,
            openPrice = 1000,
            highPrice = 1000,
            lowPrice = 1000,
            closePrice = 1000,
            volume = volume,
            changeRate = 0.0,
        )

    context("상대거래량(RVOL)") {
        test("당일 누적이 직전 거래일 평균의 몇 배인지 계산한다") {
            // 최신순(내림차순) 입력: 당일=300, 직전 3일 평균=100 → 3.0배
            val candles = DailyCandles(
                listOf(
                    candle(today, 300),
                    candle(today.minusDays(1), 100),
                    candle(today.minusDays(2), 100),
                    candle(today.minusDays(3), 100),
                ),
            )
            candles.relativeVolume(today, 3) shouldBe 3.0
        }

        test("베이스라인은 당일을 제외하고 최신 N거래일만 쓴다") {
            // lookback=2면 당일 직전 2일(200,200)만 평균=200, 더 과거(1000)는 무시 → 400/200=2.0
            val candles = DailyCandles(
                listOf(
                    candle(today, 400),
                    candle(today.minusDays(1), 200),
                    candle(today.minusDays(2), 200),
                    candle(today.minusDays(3), 1000),
                ),
            )
            candles.relativeVolume(today, 2) shouldBe 2.0
        }

        test("당일 캔들이 없으면 null") {
            val candles = DailyCandles(
                listOf(
                    candle(today.minusDays(1), 100),
                    candle(today.minusDays(2), 100),
                ),
            )
            candles.relativeVolume(today, 20) shouldBe null
        }

        test("직전 거래일 데이터가 없으면 null") {
            val candles = DailyCandles(listOf(candle(today, 300)))
            candles.relativeVolume(today, 20) shouldBe null
        }
    }
})
