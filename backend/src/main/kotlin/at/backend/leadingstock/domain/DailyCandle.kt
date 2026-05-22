package at.backend.leadingstock.domain

import java.time.LocalDate

data class DailyCandle(
    val date: LocalDate,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val closePrice: Long,
    val volume: Long,
    val changeRate: Double, // 등락률 (%)
)
