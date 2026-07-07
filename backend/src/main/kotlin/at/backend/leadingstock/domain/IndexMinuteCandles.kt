package at.backend.leadingstock.domain

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 업종 지수 한 시점의 시세(10초 틱). [value]는 지수값, [volume]은 그 틱 거래량. */
data class IndexTick(val at: LocalDateTime, val value: Double, val volume: Long = 0)

/** 합성된 지수 1분봉 — 그 분 첫 틱이 시가, 마지막 틱이 종가, 고/저는 분 내 최대·최소. */
data class IndexMinuteCandle(
    val minute: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

/**
 * 지수 5분봉 20이평 반등(상향 돌파) 판정.
 * [crossedUp]: 최신 확정 5분봉이 20이평을 아래→위로 뚫은 봉인지. [belowBand]: 이평보다 마진 이상 아래인지(재무장).
 */
data class IndexMa20Signal(val crossedUp: Boolean, val belowBand: Boolean)

/**
 * 지수 1분봉 모음 — 시간 오름차순으로 정규화해 보관한다.
 * 10초 틱을 분 단위로 묶어 만들며(시가=첫 틱, 종가=마지막 틱), 5분봉 20이평 반등을 판정한다.
 */
class IndexMinuteCandles(candles: List<IndexMinuteCandle>) {

    private val ordered = candles.sortedBy { it.minute }

    /** 시간 오름차순 봉 목록. */
    fun candles(): List<IndexMinuteCandle> = ordered

    /**
     * 1분봉을 [intervalMinutes]분봉으로 합성한 뒤, 진행 중인 마지막 봉을 뺀 확정 봉 이력에서 최신 확정봉이
     * [period]-이평을 아래→위로 뚫은 반등봉인지 직접 판정한다. 확정 봉이 [period]+1개 미만이면 null.
     * 종목 돌림(MinuteCandles.movingAverage)과 동일 규칙.
     */
    fun movingAverage(intervalMinutes: Int, period: Int, rearmMargin: Double): IndexMa20Signal? {
        val bars = aggregate(intervalMinutes).dropLast(1) // 마지막 봉은 진행 중 — 직전 확정까지만
        if (bars.size < period + 1) return null
        val latest = bars.last()
        val prev = bars[bars.size - 2]
        val maLatest = bars.takeLast(period).map { it.close }.average()
        val maPrev = bars.subList(bars.size - period - 1, bars.size - 1).map { it.close }.average()
        val crossedUp = prev.close <= maPrev && latest.close > maLatest
        val belowBand = latest.close < maLatest * (1 - rearmMargin)
        return IndexMa20Signal(crossedUp, belowBand)
    }

    /** 1분봉을 [intervalMinutes]분 경계로 묶어 종가=끝봉 종가로 합성(반등 판정은 종가만 사용). */
    private fun aggregate(intervalMinutes: Int): List<IndexMinuteCandle> =
        ordered.groupBy { it.minute.withMinute(it.minute.minute / intervalMinutes * intervalMinutes).withSecond(0).withNano(0) }
            .toSortedMap()
            .map { (_, group) -> group.last() }

    companion object {
        /** 10초 틱들을 분 단위로 묶어 1분봉으로 합성한다. 같은 분 안에서 첫 틱=시가, 마지막 틱=종가. */
        fun fromTicks(ticks: List<IndexTick>): IndexMinuteCandles {
            val candles = ticks
                .groupBy { it.at.truncatedTo(ChronoUnit.MINUTES) }
                .map { (minute, group) ->
                    val sorted = group.sortedBy { it.at }
                    IndexMinuteCandle(
                        minute = minute,
                        open = sorted.first().value,
                        high = sorted.maxOf { it.value },
                        low = sorted.minOf { it.value },
                        close = sorted.last().value,
                        volume = sorted.sumOf { it.volume },
                    )
                }
            return IndexMinuteCandles(candles)
        }
    }
}
