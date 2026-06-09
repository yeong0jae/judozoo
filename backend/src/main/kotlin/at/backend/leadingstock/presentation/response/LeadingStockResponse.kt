package at.backend.leadingstock.presentation.response

import java.time.LocalDateTime

// --- 후보 리스트 API ---

data class CandidateStocksResponse(
    val queriedAt: LocalDateTime,
    val totalCount: Int,
    val stocks: List<CandidateStockItem>,
)

data class CandidateStockItem(
    val rank: Int,
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val accumulatedTradingValue: Long,
)

// --- 종목 상세 API ---

data class LeadingStockDetailResponse(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val relativeVolume: Double?, // 풀데이 RVOL — 당일 누적/직전 20일 평균 거래량. 데이터 없으면 null
    val filterResults: List<FilterResultItem>,
)

data class FilterResultItem(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
)

// --- 투자자 추이 API ---

/** 단위: 백만원. 양수=순매수, 음수=순매도. NXT 컬럼은 NXT 거래소 단독 값. */
data class InvestorTrendDayItem(
    val date: String,         // yyyy-MM-dd
    val individualNet: Long,
    val foreignNet: Long,
    val institutionNet: Long,
    val individualNetNxt: Long,
    val foreignNetNxt: Long,
    val institutionNetNxt: Long,
)
