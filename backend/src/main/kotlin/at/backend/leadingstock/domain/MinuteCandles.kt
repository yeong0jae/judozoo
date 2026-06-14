package at.backend.leadingstock.domain

import java.time.LocalDateTime

/**
 * 직전 스윙 고점 돌파 매매 시그널.
 * [gapRate]: `(고점 - 현재가) / 현재가 * 100`. 현재가가 얼마나 올라야 전고점에 닿는지(항상 양수).
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
     * 직전 스윙 고점 돌파 시그널. 머리 위 저항이 없거나 분봉이 부족하면 null.
     *
     * 프랙탈 피벗 방식: 어떤 봉의 고가가 좌우 각 [pivotWindow]봉의 고가보다 모두 높으면 봉우리(피벗 고점)로 본다.
     * 그중 **현재가보다 높은 가장 최근 피벗**을 직전 스윙 고점(=돌파 대상 저항)으로 고른다.
     * 끝자락 [pivotWindow]봉은 우측 봉이 부족해 피벗으로 확정되지 않는다(검출 지연).
     */
    fun lastSwingHighSignal(currentPrice: Long, pivotWindow: Int): SwingHighSignal? {
        if (currentPrice <= 0 || ordered.size < pivotWindow * 2 + 1) return null

        var pivot: MinuteCandle? = null
        for (i in pivotWindow until ordered.size - pivotWindow) {
            val high = ordered[i].highPrice
            if (high <= currentPrice) continue // 현재가 위 저항만
            val higherThanLeft = (i - pivotWindow until i).all { ordered[it].highPrice < high }
            val higherThanRight = (i + 1..i + pivotWindow).all { ordered[it].highPrice < high }
            if (higherThanLeft && higherThanRight) pivot = ordered[i] // 더 최근 피벗으로 갱신
        }

        val peak = pivot ?: return null
        return SwingHighSignal(
            peakPrice = peak.highPrice,
            peakAt = peak.dateTime,
            gapRate = (peak.highPrice - currentPrice).toDouble() / currentPrice * 100,
        )
    }
}
