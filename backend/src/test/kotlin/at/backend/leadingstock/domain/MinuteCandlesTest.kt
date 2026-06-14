package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MinuteCandlesTest : FunSpec({

    val base = LocalDateTime.of(2026, 6, 12, 9, 0)

    // 피벗 판정은 고가만 사용 — 나머지 필드는 영향 없음
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

    context("직전 스윙 고점 (프랙탈 피벗, 좌우 2봉)") {
        test("좌우 봉보다 높은 봉우리를 전고점으로 잡는다") {
            // 분2의 105가 좌우 2봉보다 높음 → 피벗, 현재가 104면 (105-104)/104
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 102), candle(2, 105), candle(3, 103), candle(4, 101),
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2)!!
            signal.peakPrice shouldBe 105
            signal.peakAt shouldBe base.plusMinutes(2)
            signal.gapRate shouldBe ((105 - 104).toDouble() / 104 * 100 plusOrMinus 0.001)
        }

        test("현재가보다 높은 피벗이 여럿이면 더 최근 것을 고른다") {
            // 분2(105)·분6(108) 둘 다 피벗·현재가 위 → 더 최근인 분6 선택
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 102), candle(2, 105), candle(3, 103),
                    candle(4, 101), candle(5, 104), candle(6, 108), candle(7, 106), candle(8, 102),
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2)!!
            signal.peakPrice shouldBe 108
            signal.peakAt shouldBe base.plusMinutes(6)
        }

        test("더 최근 피벗이 더 낮아도 그것을 고른다") {
            // 분2(108)이 더 높지만, 현재가 위 가장 최근 피벗은 분6(106)
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 102), candle(2, 108), candle(3, 103),
                    candle(4, 101), candle(5, 104), candle(6, 106), candle(7, 105), candle(8, 102),
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2)!!
            signal.peakPrice shouldBe 106
            signal.peakAt shouldBe base.plusMinutes(6)
        }

        test("같은 고가가 이어지는 쌍고점은 첫 봉을 피벗으로 잡는다") {
            // 분2·분3이 동일 고가 108(쌍고점) → 첫 봉(분2)이 피벗
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 102), candle(2, 108), candle(3, 108),
                    candle(4, 103), candle(5, 101),
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2)!!
            signal.peakPrice shouldBe 108
            signal.peakAt shouldBe base.plusMinutes(2)
        }

        test("현재가보다 낮은 피벗만 있으면 머리 위 저항이 없어 null") {
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 102), candle(2, 105), candle(3, 103), candle(4, 101),
                ),
            )
            candles.lastSwingHighSignal(currentPrice = 110, pivotWindow = 2) shouldBe null
        }

        test("끝자락 상승 꼬리는 우측 봉이 부족해 확정되지 않는다") {
            // 분5(108)·분6(110)이 더 높지만 끝자락이라 미확정 → 확정된 분2(105)로 폴백
            val candles = MinuteCandles(
                listOf(
                    candle(0, 100), candle(1, 102), candle(2, 105), candle(3, 103),
                    candle(4, 104), candle(5, 108), candle(6, 110),
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2)!!
            signal.peakPrice shouldBe 105
            signal.peakAt shouldBe base.plusMinutes(2)
        }

        test("봉 수가 좌우 윈도우를 채우지 못하면 null") {
            val candles = MinuteCandles(
                listOf(candle(0, 100), candle(1, 102), candle(2, 105), candle(3, 103)),
            )
            candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2) shouldBe null
        }

        test("분봉이 없으면 null") {
            MinuteCandles(emptyList()).lastSwingHighSignal(currentPrice = 1000, pivotWindow = 2) shouldBe null
        }

        test("입력이 시간 역순이어도 정렬해 동일하게 판정한다") {
            val candles = MinuteCandles(
                listOf(
                    candle(4, 101), candle(3, 103), candle(2, 105), candle(1, 102), candle(0, 100),
                ),
            )
            val signal = candles.lastSwingHighSignal(currentPrice = 104, pivotWindow = 2)!!
            signal.peakPrice shouldBe 105
            signal.peakAt shouldBe base.plusMinutes(2)
        }
    }
})
