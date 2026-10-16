package judozoo.leadingstock.domain

import java.time.LocalDateTime

/** 업종 지수 한 시점의 시세(10초 틱). [value]는 지수값, [volume]은 그 틱 거래량. 시각은 KST 벽시계다. */
data class IndexTick(
    val at: LocalDateTime,
    val value: Double,
    val volume: Long = 0,
)
