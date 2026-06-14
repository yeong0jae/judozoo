package at.backend.leadingstock.domain

/** 당일 분봉 모음 — 시간 오름차순으로 정규화해 보관한다. */
class MinuteCandles(candles: List<MinuteCandle>) {

    private val ordered = candles.sortedBy { it.dateTime }

    /**
     * 직전 스윙 고점 돌파까지 남은 상승률(%). 양수면 아직 못 미쳤고, 음수면 이미 돌파한 상태.
     *
     * ZigZag 방식으로 봉우리를 사후 확정한다: 신고가를 후보로 갱신해 나가다가,
     * 후보 대비 [pullbackRate]% 이상 눌린 봉이 나오면 그 후보를 스윙 고점으로 확정한다.
     * 이 눌림폭이 노이즈 필터 역할을 해, 의미 있는 봉우리만 골라낸다.
     *
     * 가장 최근에 확정된 고점을 기준으로 `(고점 - 현재가) / 현재가 * 100`을 낸다.
     * 확정된 고점이 없거나(의미 있는 눌림이 한 번도 없음) 분봉이 없으면 null.
     */
    fun gapRateToLastSwingHigh(currentPrice: Long, pullbackRate: Double): Double? {
        if (ordered.isEmpty() || currentPrice <= 0) return null

        val threshold = 1 - pullbackRate / 100
        var candidate = ordered.first().highPrice
        var confirmedPeak: Long? = null
        for (candle in ordered) {
            if (candle.highPrice > candidate) {
                candidate = candle.highPrice
            } else if (candle.lowPrice <= candidate * threshold) {
                confirmedPeak = candidate
                candidate = candle.highPrice
            }
        }

        val peak = confirmedPeak ?: return null
        return (peak - currentPrice).toDouble() / currentPrice * 100
    }
}
