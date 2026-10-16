package judozoo.leadingstock.domain

import java.time.LocalDate

class DailyCandles(private val candles: List<DailyCandle>) {
    fun relativeVolumeFrom(currentVolume: Long?, today: LocalDate, lookback: Int): Double? {
        if (currentVolume == null) return null
        val baseline = candles.filter { it.date < today }.take(lookback).map { it.volume }
        if (baseline.isEmpty()) return null
        val avg = baseline.average()
        if (avg <= 0) return null
        return currentVolume / avg
    }

    fun recentHigh(before: LocalDate, days: Int): Long? =
        candles.filter { it.date < before }.sortedByDescending { it.date }.take(days).maxOfOrNull { it.highPrice }?.takeIf { it > 0 }

    fun gapFromRecentHigh(price: Long, before: LocalDate, days: Int): Double? =
        recentHigh(before, days)?.let { (price - it).toDouble() / it * 100 }
}
