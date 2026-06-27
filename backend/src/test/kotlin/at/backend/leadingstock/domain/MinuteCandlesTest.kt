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

    // OHLC를 모두 지정하는 봉 (사후 라벨은 종가·고가·저가를 본다)
    fun ohlc(minute: Int, open: Long, high: Long, low: Long, close: Long) =
        MinuteCandle(
            dateTime = base.plusMinutes(minute.toLong()),
            openPrice = open,
            highPrice = high,
            lowPrice = low,
            closePrice = close,
            volume = 0,
            tradingValue = 0,
        )

    context("시그널 사후 라벨") {
        // 신호 09:00, 진입가 1000. 이후 봉들로 각 수익률이 결정된다.
        val candles = MinuteCandles(
            listOf(
                ohlc(0, 1000, 1000, 1000, 1000),  // 신호봉
                ohlc(5, 1000, 1200, 1000, 1100),  // +5분 종가 1100, 고가 1200
                ohlc(10, 1100, 1100, 1050, 1050), // +10분 종가 1050
                ohlc(30, 1050, 1050, 880, 900),   // +30분 종가 900, 저가 880
                ohlc(40, 900, 1030, 900, 1020),   // 마지막 = 당일 종가 1020
            ),
        )

        test("신호 이후 각 호라이즌 시점 봉 종가로 진입가 대비 수익률을 낸다") {
            val l = candles.labelFor(base, entryPrice = 1000)
            l.ret5m shouldBe (10.0 plusOrMinus 0.001)    // 1100
            l.ret10m shouldBe (5.0 plusOrMinus 0.001)    // 1050
            l.ret30m shouldBe (-10.0 plusOrMinus 0.001)  // 900
            l.retClose shouldBe (2.0 plusOrMinus 0.001)  // 1020
            l.mfe shouldBe (20.0 plusOrMinus 0.001)      // 최고가 1200
            l.mae shouldBe (-12.0 plusOrMinus 0.001)     // 최저가 880
        }

        test("측정 시점까지 봉이 없으면 그 호라이즌만 null(측정 불가)") {
            // 신호 후 10분치만 있으면 +30분·종가는 그날 마지막 봉으로, +30분은 측정 불가
            val short = MinuteCandles(
                listOf(
                    ohlc(0, 1000, 1000, 1000, 1000),
                    ohlc(5, 1000, 1100, 1000, 1100),
                    ohlc(10, 1100, 1100, 1100, 1050),
                ),
            )
            val l = short.labelFor(base, entryPrice = 1000)
            l.ret5m shouldBe (10.0 plusOrMinus 0.001)
            l.ret10m shouldBe (5.0 plusOrMinus 0.001)
            l.ret30m shouldBe null
        }

        test("신호 이전 봉은 고점·저점·종가 계산에서 제외한다") {
            // 08:55 봉이 고가 9999여도 신호(09:00) 이전이라 mfe에 들어가지 않는다
            val withPrior = MinuteCandles(
                listOf(ohlc(-5, 1000, 9999, 1000, 1000)) + candles.let {
                    listOf(
                        ohlc(0, 1000, 1000, 1000, 1000),
                        ohlc(5, 1000, 1200, 1000, 1100),
                        ohlc(40, 900, 1030, 900, 1020),
                    )
                },
            )
            withPrior.labelFor(base, entryPrice = 1000).mfe shouldBe (20.0 plusOrMinus 0.001)
        }

        test("분봉이 없으면 모두 null") {
            val l = MinuteCandles(emptyList()).labelFor(base, entryPrice = 1000)
            l.ret5m shouldBe null
            l.retClose shouldBe null
            l.mfe shouldBe null
        }

        test("진입가가 0 이하면 모두 null") {
            val l = candles.labelFor(base, entryPrice = 0)
            l.ret5m shouldBe null
            l.mfe shouldBe null
        }
    }
})
