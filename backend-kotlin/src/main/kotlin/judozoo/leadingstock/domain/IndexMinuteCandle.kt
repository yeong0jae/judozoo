package judozoo.leadingstock.domain

import java.time.LocalDateTime

/** 합성된 지수 1분봉 — 그 분 첫 틱이 시가, 마지막 틱이 종가, 고저는 분 내 극값. */
data class IndexMinuteCandle(
    val minute: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
