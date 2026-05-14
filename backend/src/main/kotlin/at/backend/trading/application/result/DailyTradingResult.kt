package at.backend.trading.application.result

import at.backend.trading.domain.cycle.TradingCycle
import java.time.LocalDateTime

data class DailyTradingResult(
    val cycleId: Long,
    val stockCode: String,
    val stockName: String,
    val status: String,
    val closeReason: String?,
    val profitRate: Double?,
    val profitAmount: Long?,
    val createdAt: LocalDateTime,
    val closedAt: LocalDateTime?,
) {
    companion object {
        fun from(cycle: TradingCycle, profitRate: Double?, profitAmount: Long?) = DailyTradingResult(
            cycleId = cycle.id,
            stockCode = cycle.stockCode,
            stockName = cycle.stockName,
            status = cycle.status.name,
            closeReason = cycle.closeReason?.name,
            profitRate = profitRate,
            profitAmount = profitAmount,
            createdAt = cycle.createdAt,
            closedAt = cycle.closedAt,
        )
    }
}
