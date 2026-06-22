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

/**
 * 분봉 거래대금 스파이크 — 최신 1분봉 거래대금이 직전 평균 대비 몇 배인지.
 * [ratio]=1.0이면 평소 수준, 클수록 순간 수급이 몰린 것.
 */
data class VolumeSpike(
    val latestTradingValue: Long,
    val at: LocalDateTime,
    val ratio: Double,
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
        )
    }

    /**
     * [since] 이후(포함) 봉 중 임계([minRatio]·[minTradingValue])를 넘긴 봉 중 **배율이 가장 큰 봉**의 스파이크.
     * 각 봉은 자기 직전 [baselineBars]봉 평균과 비교한다.
     *
     * 화면이 1분 단위로만 갱신돼도 직전 몇 분 사이의 스파이크를 놓치지 않도록 한 봉이 아니라 시간 창으로 본다.
     * [since]는 호출자가 "지금 - N분"으로 준다(봉 개수가 아니라 벽시계 기준이라, 장 마감 후엔 자연히 빈다).
     * 창 안에 임계를 넘긴 봉이 없으면 null.
     */
    fun recentVolumeSpike(
        baselineBars: Int,
        since: LocalDateTime,
        minRatio: Double,
        minTradingValue: Long,
    ): VolumeSpike? {
        var best: VolumeSpike? = null
        for (i in 1 until ordered.size) {
            val bar = ordered[i]
            if (bar.dateTime < since || bar.tradingValue < minTradingValue) continue
            val avg = ordered.subList(maxOf(0, i - baselineBars), i).map { it.tradingValue }.average()
            if (avg <= 0) continue
            val ratio = bar.tradingValue / avg
            if (ratio < minRatio) continue
            if (best == null || ratio > best.ratio) {
                best = VolumeSpike(latestTradingValue = bar.tradingValue, at = bar.dateTime, ratio = ratio)
            }
        }
        return best
    }
}
