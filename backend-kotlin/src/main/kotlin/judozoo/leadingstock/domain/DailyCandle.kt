package judozoo.leadingstock.domain

import java.time.LocalDate

data class DailyCandle(
    val date: LocalDate,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val closePrice: Long,
    val volume: Long,
    /** 등락률(%) */
    val changeRate: Double,
)
