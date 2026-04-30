package at.backend.trading.domain

import java.time.Instant

data class Bar(
    val stockCode: String,
    val openPrice: Int,
    val closePrice: Int,
    val startTime: Instant,
    val endTime: Instant,
)
