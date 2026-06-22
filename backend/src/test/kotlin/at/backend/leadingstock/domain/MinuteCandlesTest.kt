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

    // 거래대금만 지정하는 봉 (스파이크 판정은 tradingValue만 사용)
    fun tvCandle(minute: Int, tradingValue: Long) =
        MinuteCandle(
            dateTime = base.plusMinutes(minute.toLong()),
            openPrice = 0,
            highPrice = 0,
            lowPrice = 0,
            closePrice = 0,
            volume = 0,
            tradingValue = tradingValue,
        )

    context("분봉 거래대금 스파이크") {
        test("최신 봉 거래대금이 직전 평균의 N배면 배율 N") {
            // 직전 3봉 평균 100, 최신 500 → 5배
            val candles = MinuteCandles(
                listOf(
                    tvCandle(0, 100), tvCandle(1, 100), tvCandle(2, 100), tvCandle(3, 500),
                ),
            )
            val spike = candles.volumeSpike(baselineBars = 3)!!
            spike.latestTradingValue shouldBe 500
            spike.at shouldBe base.plusMinutes(3)
            spike.ratio shouldBe (5.0 plusOrMinus 0.001)
        }

        test("직전 평균은 baselineBars 개로 제한된다") {
            // baselineBars=2 → 직전 2봉(분2·분3) 평균 200, 최신 400 → 2배 (분0·분1 제외)
            val candles = MinuteCandles(
                listOf(
                    tvCandle(0, 1000), tvCandle(1, 1000),
                    tvCandle(2, 200), tvCandle(3, 200), tvCandle(4, 400),
                ),
            )
            candles.volumeSpike(baselineBars = 2)!!.ratio shouldBe (2.0 plusOrMinus 0.001)
        }

        test("봉이 1개뿐이면 null") {
            MinuteCandles(listOf(tvCandle(0, 100))).volumeSpike(baselineBars = 20) shouldBe null
        }

        test("직전 평균이 0이면 null") {
            val candles = MinuteCandles(listOf(tvCandle(0, 0), tvCandle(1, 500)))
            candles.volumeSpike(baselineBars = 20) shouldBe null
        }

        test("스파이크 봉이 양봉이면 매수, 음봉이면 매도로 가른다") {
            fun bar(minute: Int, open: Long, close: Long, tv: Long) =
                MinuteCandle(
                    dateTime = base.plusMinutes(minute.toLong()),
                    openPrice = open,
                    highPrice = maxOf(open, close),
                    lowPrice = minOf(open, close),
                    closePrice = close,
                    volume = 0,
                    tradingValue = tv,
                )
            // 직전 3봉 평균 100, 최신 500(5배). 종가>시가 → 매수
            val bull = MinuteCandles(
                listOf(bar(0, 100, 100, 100), bar(1, 100, 100, 100), bar(2, 100, 100, 100), bar(3, 100, 120, 500)),
            )
            bull.volumeSpike(baselineBars = 3)!!.direction shouldBe SpikeDirection.BUY
            // 종가<시가 → 매도
            val bear = MinuteCandles(
                listOf(bar(0, 100, 100, 100), bar(1, 100, 100, 100), bar(2, 100, 100, 100), bar(3, 120, 100, 500)),
            )
            bear.volumeSpike(baselineBars = 3)!!.direction shouldBe SpikeDirection.SELL
        }
    }

    context("최근 창 스파이크") {
        test("창 안에서 배율이 가장 큰 봉을 그 시각과 함께 고른다") {
            // 분3이 직전 2봉(100,100) 대비 9배 — 가장 최신 봉(분5)이 아니어도 창 안 최대 배율을 잡는다
            val candles = MinuteCandles(
                listOf(
                    tvCandle(0, 100), tvCandle(1, 100), tvCandle(2, 100),
                    tvCandle(3, 900), tvCandle(4, 100), tvCandle(5, 600),
                ),
            )
            val spike = candles.recentVolumeSpike(
                baselineBars = 2, since = base.plusMinutes(3), minRatio = 3.0, minTradingValue = 100,
            )!!
            spike.at shouldBe base.plusMinutes(3)
            spike.latestTradingValue shouldBe 900
            spike.ratio shouldBe (9.0 plusOrMinus 0.001)
        }

        test("창 밖에서 터진 스파이크는 잡지 않는다") {
            // 스파이크는 분2 — 창(since=분3) 밖이라 무시되고 창 안엔 임계 초과 봉이 없다
            val candles = MinuteCandles(
                listOf(
                    tvCandle(0, 100), tvCandle(1, 100), tvCandle(2, 900),
                    tvCandle(3, 100), tvCandle(4, 100), tvCandle(5, 100),
                ),
            )
            candles.recentVolumeSpike(
                baselineBars = 2, since = base.plusMinutes(3), minRatio = 3.0, minTradingValue = 100,
            ) shouldBe null
        }

        test("배율은 넘겨도 거래대금 임계 미달이면 제외한다") {
            // 분3은 6배지만 거래대금 60 < 임계 1000 → 후보 없음
            val candles = MinuteCandles(
                listOf(tvCandle(0, 10), tvCandle(1, 10), tvCandle(2, 10), tvCandle(3, 60)),
            )
            candles.recentVolumeSpike(
                baselineBars = 2, since = base, minRatio = 3.0, minTradingValue = 1000,
            ) shouldBe null
        }

        test("창 안에 임계를 넘긴 봉이 없으면 null") {
            val candles = MinuteCandles(
                listOf(tvCandle(0, 100), tvCandle(1, 100), tvCandle(2, 100), tvCandle(3, 100)),
            )
            candles.recentVolumeSpike(
                baselineBars = 2, since = base, minRatio = 3.0, minTradingValue = 100,
            ) shouldBe null
        }
    }
})
