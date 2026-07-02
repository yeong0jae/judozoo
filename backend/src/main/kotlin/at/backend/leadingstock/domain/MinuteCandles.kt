package at.backend.leadingstock.domain

import java.time.LocalDateTime

/**
 * 당일 고가(전고점) 돌파 매매 시그널.
 * [gapRate]: `(고점 - 현재가) / 현재가 * 100`. 현재가가 전고점까지 얼마나 남았는지(%).
 *            이미 전고점에 도달/돌파했으면 0 이하.
 * [peakAt]: 그 고점이 형성된 분봉의 시각.
 */
data class SwingHighSignal(
    val peakPrice: Long,
    val peakAt: LocalDateTime,
    val gapRate: Double,
)

/** 스파이크 봉의 방향 — 종가>시가면 매수, 종가<시가면 매도, 같으면 보합. */
enum class SpikeDirection { BUY, SELL, FLAT }

/**
 * 분봉 거래대금 스파이크 — 최신 1분봉 거래대금이 직전 평균 대비 몇 배인지.
 * [ratio]=1.0이면 평소 수준, 클수록 순간 수급이 몰린 것. [direction]은 그 봉의 양/음봉으로 매수/매도를 가른다.
 */
data class VolumeSpike(
    val latestTradingValue: Long,
    val at: LocalDateTime,
    val ratio: Double,
    val direction: SpikeDirection,
)

/**
 * 5분봉 20이평 돌림(상향 돌파) 판정.
 * [crossedUp]: 최신 확정 5분봉이 20이평을 아래에서 위로 처음 뚫은 봉인지
 *              (직전 확정봉 종가 ≤ 그때 20이평 AND 최신 확정봉 종가 > 지금 20이평).
 * [ma20]: 최신 확정봉 시점 20이평값(원, 반올림).
 */
data class MovingAverageReading(val crossedUp: Boolean, val ma20: Long)

/** 당일 분봉 모음 — 시간 오름차순으로 정규화해 보관한다. */
class MinuteCandles(candles: List<MinuteCandle>) {

    private val ordered = candles.sortedBy { it.dateTime }

    /**
     * 당일 고가 돌파 시그널. 분봉이 없으면 null.
     *
     * 당일 분봉 중 최고가를 전고점(돌파 대상 저항)으로 본다. 동일 최고가가 여러 번 나오면
     * 처음 형성된 봉을 전고점 형성 시각으로 잡는다(시간 오름차순이라 maxByOrNull이 첫 봉 반환).
     */
    fun dayHighSignal(currentPrice: Long): SwingHighSignal? {
        if (currentPrice <= 0) return null

        val peak = ordered.maxByOrNull { it.highPrice } ?: return null
        return SwingHighSignal(
            peakPrice = peak.highPrice,
            peakAt = peak.dateTime,
            gapRate = (peak.highPrice - currentPrice).toDouble() / currentPrice * 100,
        )
    }

    /**
     * 최신 1분봉 거래대금이 직전 [baselineBars]봉 평균 대비 몇 배인지.
     * 봉이 2개 미만이거나 직전 평균이 0이면 null.
     */
    fun volumeSpike(baselineBars: Int): VolumeSpike? {
        if (ordered.size < 2) return null
        val latest = ordered.last()
        val baseline = ordered.dropLast(1).takeLast(baselineBars)
        val avg = baseline.map { it.tradingValue }.average()
        if (avg <= 0) return null
        return VolumeSpike(
            latestTradingValue = latest.tradingValue,
            at = latest.dateTime,
            ratio = latest.tradingValue / avg,
            direction = latest.spikeDirection(),
        )
    }

    private fun MinuteCandle.spikeDirection() = when {
        closePrice > openPrice -> SpikeDirection.BUY
        closePrice < openPrice -> SpikeDirection.SELL
        else -> SpikeDirection.FLAT
    }

    /**
     * 1분봉을 [intervalMinutes]분봉으로 합성한 뒤, 마지막(진행 중) 봉을 뺀 **확정 봉 이력**에서
     * 최신 확정봉이 [period]-이평을 아래→위로 돌파한 봉("돌림봉")인지 직접 판정한다.
     * 직전 확정봉의 이평까지 필요하므로 확정 봉이 [period]+1개 미만이면 null.
     *
     * 폴러 관측 이력이 아니라 봉 데이터 자체로 크로스를 잡으므로, 방금 후보에 든 종목이라도
     * 최신 확정봉이 크로스면 잡히고, 이미 이평 위에 쭉 있던 종목은 크로스봉이 아니라 잡히지 않는다.
     */
    fun movingAverage(intervalMinutes: Int, period: Int): MovingAverageReading? {
        val bars = aggregate(intervalMinutes).dropLast(1) // 마지막 봉은 진행 중 — 직전 확정 봉까지만
        if (bars.size < period + 1) return null
        val latest = bars.last()
        val prev = bars[bars.size - 2]
        val maLatest = bars.takeLast(period).map { it.closePrice }.average()
        val maPrev = bars.subList(bars.size - period - 1, bars.size - 1).map { it.closePrice }.average()
        val crossedUp = prev.closePrice <= maPrev && latest.closePrice > maLatest
        return MovingAverageReading(crossedUp = crossedUp, ma20 = Math.round(maLatest))
    }

    /**
     * 1분봉을 [intervalMinutes]분 경계로 묶어 시가=첫봉 시가, 종가=끝봉 종가, 고저=구간 극값,
     * 거래량·거래대금=합으로 합성한다. 봉 시각은 구간 시작 시각. 날짜·구간이 다르면 다른 봉.
     */
    private fun aggregate(intervalMinutes: Int): List<MinuteCandle> =
        ordered.groupBy { it.dateTime.withMinute(it.dateTime.minute / intervalMinutes * intervalMinutes).withSecond(0).withNano(0) }
            .toSortedMap()
            .map { (start, group) ->
                MinuteCandle(
                    dateTime = start,
                    openPrice = group.first().openPrice,
                    highPrice = group.maxOf { it.highPrice },
                    lowPrice = group.minOf { it.lowPrice },
                    closePrice = group.last().closePrice,
                    volume = group.sumOf { it.volume },
                    tradingValue = group.sumOf { it.tradingValue },
                )
            }
}
