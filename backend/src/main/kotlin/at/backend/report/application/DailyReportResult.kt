package at.backend.report.application

import java.time.LocalDateTime

/**
 * 일별 실적 — 사이클 1건 단위 집계 (수수료/세금 포함).
 *
 * 기존 [at.backend.trading.application.result.DailyTradingResult]는 단순 profitAmount만 노출하나,
 * Phase 6의 실적 화면은 수수료/세금 분리와 매수→매도가가 필요해 본 DTO를 별도로 둔다.
 */
data class DailyReportResult(
    val commandId: Long,
    val stockCode: String,
    val stockName: String,
    val status: String,
    val closeReason: String?,
    val createdAt: LocalDateTime,
    val closedAt: LocalDateTime?,
    val avgBuyPrice: Long?,
    val avgSellPrice: Long?,
    val totalFee: Long,
    val totalTax: Long,
    val grossProfit: Long,
    val netProfit: Long,
    val profitRate: Double,
)
