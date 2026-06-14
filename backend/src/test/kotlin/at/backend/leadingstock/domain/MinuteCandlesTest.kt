package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MinuteCandlesTest : FunSpec({

    val base = LocalDateTime.of(2026, 6, 12, 9, 0)

    // high/low만 의미 있는 봉 — open/close/volume은 판정에 영향 없음
    fun candle(minute: Int, high: Long, low: Long) =
        MinuteCandle(
            dateTime = base.plusMinutes(minute.toLong()),
            openPrice = low,
            highPrice = high,
            lowPrice = low,
            closePrice = high,
            volume = 0,
            tradingValue = 0,
        )

    context("직전 스윙 고점까지 남은 상승률") {
        test("고점 찍고 눌림폭 이상 빠진 봉우리를 직전 고점으로 확정한다") {
            // 1050까지 오른 뒤 1025로 -2.4% 눌림 → 고점 1050 확정, 현재가 1040이면 (1050-1040)/1040
            val candles = MinuteCandles(
                listOf(
                    candle(0, high = 1000, low = 1000),
                    candle(5, high = 1050, low = 1045),
                    candle(10, high = 1045, low = 1025),
                ),
            )
            candles.lastSwingHighSignal(currentPrice = 1040, pullbackRate = 2.0)!!.gapRate shouldBe
                (0.9615 plusOrMinus 0.001)
        }

        test("가장 최근에 확정된 봉우리를 기준으로 삼는다") {
            // 1050 봉우리 확정 후 다시 1080까지 올랐다가 1050으로 눌림 → 최근 고점 1080 사용
            val candles = MinuteCandles(
                listOf(
                    candle(0, high = 1000, low = 1000),
                    candle(5, high = 1050, low = 1050),
                    candle(10, high = 1020, low = 1020), // 1050 대비 눌림 → 1050 확정
                    candle(15, high = 1080, low = 1060),
                    candle(20, high = 1070, low = 1050), // 1080 대비 눌림 → 1080 확정
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 1070, pullbackRate = 2.0)!!
            signal.peakPrice shouldBe 1080
            signal.peakAt shouldBe base.plusMinutes(15)
            signal.gapRate shouldBe ((1080 - 1070).toDouble() / 1070 * 100 plusOrMinus 0.001)
        }

        test("현재가가 직전 고점을 넘었으면 음수를 돌려준다") {
            val candles = MinuteCandles(
                listOf(
                    candle(0, high = 1000, low = 1000),
                    candle(5, high = 1050, low = 1050),
                    candle(10, high = 1020, low = 1020), // 1050 확정
                ),
            )
            candles.lastSwingHighSignal(currentPrice = 1060, pullbackRate = 2.0)!!.gapRate shouldBe
                ((1050 - 1060).toDouble() / 1060 * 100 plusOrMinus 0.001)
        }

        test("눌림폭에 못 미치는 잔흔들림만 있으면 확정 고점이 없어 null") {
            // 1050 후 1040(-0.95%)만 눌림 → 2% 미달, 봉우리 미확정
            val candles = MinuteCandles(
                listOf(
                    candle(0, high = 1000, low = 1000),
                    candle(5, high = 1050, low = 1045),
                    candle(10, high = 1048, low = 1040),
                ),
            )
            candles.lastSwingHighSignal(currentPrice = 1045, pullbackRate = 2.0) shouldBe null
        }

        test("계속 오르기만 하면 확정 고점이 없어 null") {
            val candles = MinuteCandles(
                listOf(
                    candle(0, high = 1000, low = 1000),
                    candle(5, high = 1030, low = 1020),
                    candle(10, high = 1060, low = 1050),
                ),
            )
            candles.lastSwingHighSignal(currentPrice = 1060, pullbackRate = 2.0) shouldBe null
        }

        test("분봉이 없으면 null") {
            MinuteCandles(emptyList()).lastSwingHighSignal(currentPrice = 1000, pullbackRate = 2.0) shouldBe null
        }

        test("입력이 시간 역순이어도 정렬해 동일하게 판정한다") {
            val candles = MinuteCandles(
                listOf(
                    candle(10, high = 1045, low = 1025),
                    candle(5, high = 1050, low = 1045),
                    candle(0, high = 1000, low = 1000),
                ),
            )
            candles.lastSwingHighSignal(currentPrice = 1040, pullbackRate = 2.0)!!.gapRate shouldBe
                (0.9615 plusOrMinus 0.001)
        }
    }
})
