package judozoo.leadingstock.domain

import java.time.temporal.ChronoUnit

/** 지수 1분봉 모음 — 시간 오름차순으로 정규화해 보관한다. */
class IndexMinuteCandles(candles: List<IndexMinuteCandle>) {
    private val ordered = candles.sortedBy { it.minute }

    /** 지수 분봉 이평 돌파 판정. 반등(상향)과 꺾임(하향)을 **대칭으로** 함께 담는다. */
    data class MaSignal(val crossedUp: Boolean, val belowBand: Boolean, val crossedDown: Boolean, val aboveBand: Boolean)

    fun candles(): List<IndexMinuteCandle> = ordered

    /** [intervalMinutes]분봉 종가의 [period]이평 돌파. 진행 중인 마지막 봉은 뺀다. 봉이 모자라면 null. */
    fun movingAverage(intervalMinutes: Int, period: Int, rearmMargin: Double): MaSignal? {
        val bars = aggregate(intervalMinutes).dropLast(1)
        if (bars.size < period + 1) return null
        val (prev, latest) = bars[bars.size - 2] to bars.last()
        val maLatest = bars.takeLast(period).map { it.close }.average()
        val maPrev = bars.dropLast(1).takeLast(period).map { it.close }.average()
        return MaSignal(
            crossedUp = prev.close <= maPrev && latest.close > maLatest,
            belowBand = latest.close < maLatest * (1 - rearmMargin),
            crossedDown = prev.close >= maPrev && latest.close < maLatest,
            aboveBand = latest.close > maLatest * (1 + rearmMargin),
        )
    }

    /** 구간 경계로 묶어 종가=끝봉 종가로 합성한다(판정은 종가만 쓴다). */
    private fun aggregate(intervalMinutes: Int): List<IndexMinuteCandle> =
        ordered.groupBy { it.minute.truncatedTo(ChronoUnit.HOURS).plusMinutes((it.minute.minute / intervalMinutes * intervalMinutes).toLong()) }
            .toSortedMap().values.map { it.last() }

    companion object {
        /** 10초 틱을 분 단위로 묶어 1분봉으로 합성한다. */
        fun fromTicks(ticks: List<IndexTick>): IndexMinuteCandles = IndexMinuteCandles(
            ticks.groupBy { it.at.truncatedTo(ChronoUnit.MINUTES) }.map { (minute, group) ->
                val sorted = group.sortedBy { it.at }
                IndexMinuteCandle(minute, sorted.first().value, sorted.maxOf { it.value }, sorted.minOf { it.value }, sorted.last().value, sorted.sumOf { it.volume })
            },
        )
    }
}
