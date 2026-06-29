package at.backend.overseasleadingstock.presentation.response

import java.time.LocalDateTime

/** 해외 1분봉 — 가격은 달러(소수), time은 한국 벽시계. 국내 MinuteCandleItem과 동일 JSON 구조. */
data class OverseasMinuteCandleItem(
    val time: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val tradingValue: Double,
)

/** 해외 일봉 — 가격은 달러(소수). 국내 DailyCandleChartItem과 동일 JSON 구조. */
data class OverseasDailyCandleItem(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
