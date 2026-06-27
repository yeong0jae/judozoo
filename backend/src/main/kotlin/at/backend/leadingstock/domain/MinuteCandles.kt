package at.backend.leadingstock.domain

import java.time.Duration
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
     * 시그널 1건의 사후 결과를 [entryPrice](신호 순간 현재가) 기준으로 계산한다.
     * [occurredAt] 이후의 봉만 본다 — `>= occurredAt`을 "신호 이후"로 본다.
     * 분봉이 없거나 측정 시점 봉이 없으면 해당 항목은 null(측정 불가)이다.
     */
    fun labelFor(occurredAt: LocalDateTime, entryPrice: Long): SignalLabelResult {
        if (entryPrice <= 0) return SignalLabelResult(null, null, null, null, null, null)
        val after = ordered.filter { !it.dateTime.isBefore(occurredAt) }
        if (after.isEmpty()) return SignalLabelResult(null, null, null, null, null, null)

        return SignalLabelResult(
            ret5m = after.closeAtLeast(occurredAt, 5)?.toReturn(entryPrice),
            ret10m = after.closeAtLeast(occurredAt, 10)?.toReturn(entryPrice),
            ret30m = after.closeAtLeast(occurredAt, 30)?.toReturn(entryPrice),
            retClose = after.last().closePrice.toReturn(entryPrice),
            mfe = after.maxOf { it.highPrice }.toReturn(entryPrice),
            mae = after.minOf { it.lowPrice }.toReturn(entryPrice),
        )
    }

    /** [occurredAt] + [minutes]분 시점 이후 첫 봉의 종가. 그 시점까지 봉이 없으면 null. */
    private fun List<MinuteCandle>.closeAtLeast(occurredAt: LocalDateTime, minutes: Long): Long? =
        firstOrNull { !it.dateTime.isBefore(occurredAt.plus(Duration.ofMinutes(minutes))) }?.closePrice

    private fun Long.toReturn(entryPrice: Long): Double =
        (this - entryPrice).toDouble() / entryPrice * 100
}
