package at.backend.trading.domain

import java.time.Instant

data class PriceTick(
    val stockCode: String,
    val price: Int,
    val timestamp: Instant,
)
