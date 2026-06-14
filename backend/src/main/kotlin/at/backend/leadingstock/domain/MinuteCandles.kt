package at.backend.leadingstock.domain

import java.time.LocalDateTime

/**
 * 직전 스윙 고점 돌파 매매 시그널.
 * [gapRate]: `(고점 - 현재가) / 현재가 * 100`. 양수면 아직 못 미쳤고, 음수면 이미 돌파.
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
     * 직전 스윙 고점 돌파 시그널. 확정 고점이 없거나(의미 있는 눌림이 한 번도 없음) 분봉이 없으면 null.
     *
     * ZigZag 방식으로 봉우리를 사후 확정한다: 신고가를 후보로 갱신해 나가다가,
     * 후보 대비 [pullbackRate]% 이상 눌린 봉이 나오면 그 후보를 스윙 고점으로 확정한다.
     * 이 눌림폭이 노이즈 필터 역할을 해, 의미 있는 봉우리만 골라낸다.
     */
    fun lastSwingHighSignal(currentPrice: Long, pullbackRate: Double): SwingHighSignal? {
        if (ordered.isEmpty() || currentPrice <= 0) return null

        val threshold = 1 - pullbackRate / 100
        var candidate = ordered.first().highPrice
        var candidateAt = ordered.first().dateTime
        var confirmedPeak: Long? = null
        var confirmedPeakAt: LocalDateTime? = null
        for (candle in ordered) {
            if (candle.highPrice > candidate) {
                candidate = candle.highPrice
                candidateAt = candle.dateTime
            } else if (candle.lowPrice <= candidate * threshold) {
                confirmedPeak = candidate
                confirmedPeakAt = candidateAt
                candidate = candle.highPrice
                candidateAt = candle.dateTime
            }
        }

        val peak = confirmedPeak ?: return null
        val peakAt = confirmedPeakAt ?: return null
        return SwingHighSignal(
            peakPrice = peak,
            peakAt = peakAt,
            gapRate = (peak - currentPrice).toDouble() / currentPrice * 100,
        )
    }
}
