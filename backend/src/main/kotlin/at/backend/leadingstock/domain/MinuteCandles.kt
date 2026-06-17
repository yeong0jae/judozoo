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
}
