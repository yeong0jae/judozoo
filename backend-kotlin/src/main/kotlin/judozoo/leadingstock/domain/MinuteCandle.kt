package judozoo.leadingstock.domain

import java.time.LocalDateTime

/** 1분봉. 시각은 KST 벽시계다. */
data class MinuteCandle(
    val dateTime: LocalDateTime,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val closePrice: Long,
    val volume: Long,
    val tradingValue: Long,
)
