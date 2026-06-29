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

/** 해외 종목 상세 — 필터 평가 결과(A·B·C) + 시가총액. */
data class OverseasStockDetailResponse(
    val exchange: String,
    val symbol: String,
    val name: String,
    val ename: String,
    val price: Double,
    val rate: Double,
    val marketCap: Long?, // 달러, 조회 불가 시 null
    val filterResults: List<FilterResultItem>,
)

/** 필터 한 줄 평가 — 국내 FilterResultItem과 동일 구조. */
data class FilterResultItem(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
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
