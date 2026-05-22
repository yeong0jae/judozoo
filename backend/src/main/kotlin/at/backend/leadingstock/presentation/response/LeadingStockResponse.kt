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
    val filterResults: List<FilterResultItem>,
)

data class FilterResultItem(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
)
