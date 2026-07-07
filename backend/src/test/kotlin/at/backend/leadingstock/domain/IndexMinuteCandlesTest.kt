package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class IndexMinuteCandlesTest : FunSpec({

    val base = LocalDateTime.of(2026, 6, 25, 9, 0)
    fun tick(minute: Int, second: Int, value: Double) =
        IndexTick(base.plusMinutes(minute.toLong()).plusSeconds(second.toLong()), value)

    context("틱→1분봉 합성") {
        test("같은 분의 첫 틱이 시가, 마지막 틱이 종가가 된다") {
            val candle = IndexMinuteCandles.fromTicks(
                listOf(tick(0, 30, 105.0), tick(0, 0, 100.0), tick(0, 50, 110.0)),
            ).candles().single()
            candle.minute shouldBe base
            candle.open shouldBe 100.0
            candle.close shouldBe 110.0
            candle.high shouldBe 110.0
            candle.low shouldBe 100.0
        }
    }

    context("5분봉 20이평 반등 판정") {
        // 5분봉 한 구간(1분봉 5개) — 종가만 지정하면 그 구간 5분봉 종가가 된다.
        fun fiveMinBar(bucket: Int, close: Double) = (0..4).map { m ->
            IndexMinuteCandle(base.plusMinutes((bucket * 5 + m).toLong()), close, close, close, close, 0)
        }

        test("직전 확정봉은 이평 이하였고 최신 확정봉이 이평 위로 올라서면 반등봉이다") {
            // period=3, 확정 [10,10,10,40] → 최신 이평 20, 끝봉 40>20 · 직전 이평 10, 직전봉 10<=10
            val candles = IndexMinuteCandles(
                fiveMinBar(0, 10.0) + fiveMinBar(1, 10.0) + fiveMinBar(2, 10.0) + fiveMinBar(3, 40.0) + fiveMinBar(4, 999.0),
            )
            val ma = candles.movingAverage(intervalMinutes = 5, period = 3, rearmMargin = 0.005)!!
            ma.crossedUp shouldBe true
            ma.belowBand shouldBe false
        }

        test("이미 이평 위에 쭉 있던 봉은 반등봉이 아니다") {
            val candles = IndexMinuteCandles(
                fiveMinBar(0, 10.0) + fiveMinBar(1, 10.0) + fiveMinBar(2, 40.0) + fiveMinBar(3, 50.0) + fiveMinBar(4, 999.0),
            )
            candles.movingAverage(intervalMinutes = 5, period = 3, rearmMargin = 0.005)!!.crossedUp shouldBe false
        }

        test("최신 확정봉이 이평보다 마진 이상 아래면 반등봉 아님 + 재무장 신호를 켠다") {
            val candles = IndexMinuteCandles(
                fiveMinBar(0, 40.0) + fiveMinBar(1, 40.0) + fiveMinBar(2, 40.0) + fiveMinBar(3, 10.0) + fiveMinBar(4, 999.0),
            )
            val ma = candles.movingAverage(intervalMinutes = 5, period = 3, rearmMargin = 0.005)!!
            ma.crossedUp shouldBe false
            ma.belowBand shouldBe true
        }

        test("확정 5분봉이 기간+1보다 적으면 null") {
            val candles = IndexMinuteCandles(
                fiveMinBar(0, 10.0) + fiveMinBar(1, 20.0) + fiveMinBar(2, 30.0) + fiveMinBar(3, 40.0),
            )
            candles.movingAverage(intervalMinutes = 5, period = 3, rearmMargin = 0.005).shouldBeNull()
        }
    }
})
