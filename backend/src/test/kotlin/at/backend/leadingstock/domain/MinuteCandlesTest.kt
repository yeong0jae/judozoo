package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MinuteCandlesTest : FunSpec({

    val base = LocalDateTime.of(2026, 6, 12, 9, 0)

    // 시그널 판정은 고가만 사용 — 나머지 필드는 영향 없음
    fun candle(minute: Int, high: Long) =
        MinuteCandle(
            dateTime = base.plusMinutes(minute.toLong()),
            openPrice = high,
            highPrice = high,
            lowPrice = high,
            closePrice = high,
            volume = 0,
            tradingValue = 0,
        )

    context("당일 고가 돌파 시그널") {
        test("당일 분봉 중 최고가를 전고점으로 잡고 잔여 상승률을 계산한다") {
            // 최고가는 분2의 108, 현재가 104면 (108-104)/104
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 105), candle(2, 108), candle(3, 106), candle(4, 102),
                ),
            )
            val signal = candles.dayHighSignal(currentPrice = 104)!!
            signal.peakPrice shouldBe 108
            signal.peakAt shouldBe base.plusMinutes(2)
            signal.gapRate shouldBe ((108 - 104).toDouble() / 104 * 100 plusOrMinus 0.001)
        }

        test("동일 최고가가 여러 번이면 처음 형성된 봉 시각을 잡는다") {
            // 분1·분4가 동일 최고가 108 → 먼저 형성된 분1
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 108), candle(2, 103), candle(3, 105), candle(4, 108),
                ),
            )
            val signal = candles.dayHighSignal(currentPrice = 104)!!
            signal.peakPrice shouldBe 108
            signal.peakAt shouldBe base.plusMinutes(1)
        }

        test("현재가가 당일 고가에 도달하면 잔여 상승률은 0") {
            val candles = MinuteCandles(
                listOf(candle(0, 100), candle(1, 105), candle(2, 110)),
            )
            val signal = candles.dayHighSignal(currentPrice = 110)!!
            signal.peakPrice shouldBe 110
            signal.gapRate shouldBe (0.0 plusOrMinus 0.001)
        }

        test("입력이 시간 역순이어도 정렬해 동일하게 판정한다") {
            val candles = MinuteCandles(
                listOf(
                    candle(4, 102), candle(3, 106), candle(2, 108), candle(1, 105), candle(0, 100),
                ),
            )
            val signal = candles.dayHighSignal(currentPrice = 104)!!
            signal.peakPrice shouldBe 108
            signal.peakAt shouldBe base.plusMinutes(2)
        }

        test("분봉이 없으면 null") {
            MinuteCandles(emptyList()).dayHighSignal(currentPrice = 1000) shouldBe null
        }

        test("현재가가 0 이하면 null") {
            val candles = MinuteCandles(listOf(candle(0, 100), candle(1, 105)))
            candles.dayHighSignal(currentPrice = 0) shouldBe null
        }
    }
})
