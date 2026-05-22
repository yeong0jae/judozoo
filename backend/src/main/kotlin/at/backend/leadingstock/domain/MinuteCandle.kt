package at.backend.leadingstock.domain

import java.time.LocalDateTime

data class MinuteCandle(
    val dateTime: LocalDateTime,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val closePrice: Long,
    val volume: Long,
    val tradingValue: Long,
)
