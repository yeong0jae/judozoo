package judozoo.overseasleadingstock.domain

import java.time.LocalDate

data class DayCandles(val day: LocalDate, val candles: List<OverseasMinuteCandle>) {
    companion object {
        fun byDay(candles: List<OverseasMinuteCandle>): Map<LocalDate, List<OverseasMinuteCandle>> =
            candles.sortedBy { it.dateTime }.groupBy { it.tradingDay }
    }
}
