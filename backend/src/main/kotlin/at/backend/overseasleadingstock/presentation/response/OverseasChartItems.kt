package at.backend.overseasleadingstock.presentation.response

/** 해외 일봉 — 가격은 달러(소수). 국내 DailyCandleChartItem과 동일 JSON 구조. */
data class OverseasDailyCandleItem(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
