package judozoo.leadingstock.domain

import java.time.LocalDateTime

class MinuteCandles(candles: List<MinuteCandle>) {
    private val ordered = candles.sortedBy { it.dateTime }

    data class SwingHigh(val peakPrice: Long, val peakAt: LocalDateTime, val gapRate: Double)

    data class SwingLow(val troughPrice: Long, val troughAt: LocalDateTime, val gapRate: Double)

    data class VolumeSpike(val latestTradingValue: Long, val at: LocalDateTime, val ratio: Double, val direction: SpikeDirection)

    fun peakSignal(currentPrice: Long): SwingHigh? {
        if (currentPrice <= 0 || ordered.isEmpty()) return null
        val peak = ordered.maxBy { it.highPrice }
        return SwingHigh(peak.highPrice, peak.dateTime, (peak.highPrice - currentPrice).toDouble() / currentPrice * 100)
    }

    fun troughSignal(currentPrice: Long): SwingLow? {
        if (currentPrice <= 0 || ordered.isEmpty()) return null
        val trough = ordered.minBy { it.lowPrice }
        return SwingLow(trough.lowPrice, trough.dateTime, (currentPrice - trough.lowPrice).toDouble() / currentPrice * 100)
    }

    fun volumeSpike(baselineBars: Int): VolumeSpike? {
        if (ordered.size < 2) return null
        val latest = ordered.last()
        val avg = ordered.dropLast(1).takeLast(baselineBars).map { it.tradingValue }.average()
        if (avg <= 0) return null
        val direction = if (latest.closePrice >= latest.openPrice) SpikeDirection.BUY else SpikeDirection.SELL
        return VolumeSpike(latest.tradingValue, latest.dateTime, latest.tradingValue / avg, direction)
    }
}
