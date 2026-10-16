package judozoo.leadingstock.domain

import java.time.LocalDateTime

data class IndexTick(
    val at: LocalDateTime,
    val value: Double,
    val volume: Long = 0,
)
