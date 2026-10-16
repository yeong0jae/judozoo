package judozoo.market.domain

import java.time.LocalDateTime

data class MarketCandle(
    val at: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
