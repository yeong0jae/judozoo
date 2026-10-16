package judozoo.overseasleadingstock.domain

import java.time.LocalDate
import java.time.LocalDateTime

data class OverseasMinuteCandle(
    val dateTime: LocalDateTime,
    val tradingDay: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val tradingValue: Double,
)
