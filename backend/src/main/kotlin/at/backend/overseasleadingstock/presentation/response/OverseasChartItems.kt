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

/** 해외 종목 상세 — 필터 평가 결과(A·B·C) + 시가총액 + 돌파 시그널. 패널이 이 응답만으로 완결되게 순위·대비·거래대금 포함. */
data class OverseasStockDetailResponse(
    val exchange: String,
    val symbol: String,
    val name: String,
    val ename: String,
    val rank: Int,
    val price: Double,
    val diff: Double,
    val rate: Double,
    val tradingValue: Double,
    val marketCap: Long?, // 달러, 조회 불가 시 null
    val filterResults: List<FilterResultItem>,
    val swingHighSignal: OverseasSwingHighSignal?,
)

/** 분봉 전고점 돌파 시그널. gapRate = (고점-현재가)/현재가×100, 양수=남은 상승률·음수=이미 돌파. */
data class OverseasSwingHighSignal(
    val peakPrice: Double,
    val peakAt: java.time.LocalDateTime,
    val gapRate: Double,
)

/** 필터 한 줄 평가 — 국내 FilterResultItem과 동일 구조. */
data class FilterResultItem(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
)

/** 해외 시그널 전이 로그 응답. */
data class OverseasSignalEventsResponse(
    val date: java.time.LocalDate,
    val totalCount: Int,
    val events: List<OverseasSignalEventItem>,
)

/** 해외 시그널 한 건 — eventType: BREAKOUT | BREAKOUT_IMMINENT | VOLUME_SPIKE. */
data class OverseasSignalEventItem(
    val occurredAt: java.time.LocalDateTime,
    val exchange: String,
    val symbol: String,
    val name: String,
    val eventType: String,
    val price: Double,
    val rate: Double,
    val tradingValue: Double,
    val gapRate: Double?,
    val spikeRatio: Double?,
    val minuteTradingValue: Double?,
    val spikeDirection: String?,
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
