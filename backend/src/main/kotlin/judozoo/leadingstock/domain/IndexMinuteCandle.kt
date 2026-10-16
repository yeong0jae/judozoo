package judozoo.leadingstock.domain

import java.time.LocalDateTime

data class IndexMinuteCandle(
    val minute: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
