package judozoo.leadingstock.domain

import java.time.LocalDateTime

/**
 * 분봉 모음 — 시간 오름차순으로 정규화해 보관한다.
 *
 * 구간은 넣는 쪽이 정한다 — 저항·지지는 최근 3거래일을 넘긴다.
 */
class MinuteCandles(candles: List<MinuteCandle>) {
    private val ordered = candles.sortedBy { it.dateTime }

    /**
     * 전고점(받은 분봉 구간의 최고가) 돌파 시그널. `gapRate` = (고점 − 현재가) / 현재가 × 100 — 전고점까지 남은 상승률(%).
     * 이미 도달·돌파했으면 0 이하.
     */
    data class SwingHigh(val peakPrice: Long, val peakAt: LocalDateTime, val gapRate: Double)

    /** 지지선(받은 분봉 구간의 최저가) 근접 시그널. `gapRate` = (현재가 − 저가) / 현재가 × 100. 닿았거나 깼으면 0 이하. */
    data class SwingLow(val troughPrice: Long, val troughAt: LocalDateTime, val gapRate: Double)

    /** 받은 분봉 중 최고가를 전고점으로 본다. 같은 최고가가 여러 번이면 **처음 형성된 봉**을 잡는다. */
    fun peakSignal(currentPrice: Long): SwingHigh? {
        if (currentPrice <= 0 || ordered.isEmpty()) return null
        val peak = ordered.maxBy { it.highPrice } // 동점이면 앞의 것
        return SwingHigh(peak.highPrice, peak.dateTime, (peak.highPrice - currentPrice).toDouble() / currentPrice * 100)
    }

    /** 받은 분봉 중 최저가를 지지선으로 본다. [peakSignal]과 같은 방식이다. */
    fun troughSignal(currentPrice: Long): SwingLow? {
        if (currentPrice <= 0 || ordered.isEmpty()) return null
        val trough = ordered.minBy { it.lowPrice } // 동점이면 앞의 것
        return SwingLow(trough.lowPrice, trough.dateTime, (currentPrice - trough.lowPrice).toDouble() / currentPrice * 100)
    }
}
