package at.backend.leadingstock.domain

import java.time.LocalDateTime

/**
 * 주도주 후보 종목의 시점 스냅샷. 키움 API로 수집한 시세·랭킹·기본 정보를 담는다.
 * 자동매매의 상장 종목 카탈로그 `at.backend.stock.domain.Stock`(JPA 엔티티)과는 별개 개념이라
 * "Snapshot" 접미사로 구분한다.
 */
data class LeadingStockSnapshot(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val tradingValueRank: Int,
    val accumulatedTradingValue: Long,
    val marketCap: Long = 0,
    val openingPrice: Long = 0,
    val previousClose: Long = 0,
    val highPrice: Long = 0,
    val lowPrice: Long = 0,
    val programNetBuy: Long = 0,
    val detectedAt: LocalDateTime = LocalDateTime.now(),
)
