package judozoo.overseasleadingstock.domain

import java.time.LocalDate

data class OverseasDailyCandle(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
