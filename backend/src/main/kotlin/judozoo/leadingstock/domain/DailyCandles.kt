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
}
