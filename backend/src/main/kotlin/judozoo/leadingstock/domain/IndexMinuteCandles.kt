package judozoo.leadingstock.domain

import java.time.temporal.ChronoUnit

class IndexMinuteCandles(candles: List<IndexMinuteCandle>) {
    private val ordered = candles.sortedBy { it.minute }

    data class MaSignal(val crossedUp: Boolean, val belowBand: Boolean, val crossedDown: Boolean, val aboveBand: Boolean)

    fun candles(): List<IndexMinuteCandle> = ordered

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

    private fun aggregate(intervalMinutes: Int): List<IndexMinuteCandle> =
        ordered.groupBy { it.minute.truncatedTo(ChronoUnit.HOURS).plusMinutes((it.minute.minute / intervalMinutes * intervalMinutes).toLong()) }
            .toSortedMap().values.map { it.last() }

    companion object {
        fun fromTicks(ticks: List<IndexTick>): IndexMinuteCandles = IndexMinuteCandles(
            ticks.groupBy { it.at.truncatedTo(ChronoUnit.MINUTES) }.map { (minute, group) ->
                val sorted = group.sortedBy { it.at }
                IndexMinuteCandle(minute, sorted.first().value, sorted.maxOf { it.value }, sorted.minOf { it.value }, sorted.last().value, sorted.sumOf { it.volume })
            },
        )
    }
}
