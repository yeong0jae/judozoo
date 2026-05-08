package at.backend.trading.application.result

import at.backend.trading.domain.cycle.TradingCycle

data class TradingSummaryResult(
    val cycleId: Long,
    val stockCode: String,
    val stockName: String,
    val status: String,
    val currentPrice: Long,
    val averageBuyPrice: Long,
    val profitRate: Double,
    val profitAmount: Long,
    val holdingQty: Int,
    val buyAttempt: BuyAttemptInfo,
) {
    data class BuyAttemptInfo(val completed: Int, val total: Int = 3)

    companion object {
        fun from(
            cycle: TradingCycle,
            currentPrice: Long,
            holdingQty: Int,
            averageBuyPrice: Long,
            profitRate: Double,
            profitAmount: Long,
        ) = TradingSummaryResult(
            cycleId = cycle.id,
            stockCode = cycle.stockCode,
            stockName = cycle.stockName,
            status = cycle.status.name,
            currentPrice = currentPrice,
            averageBuyPrice = averageBuyPrice,
            profitRate = profitRate,
            profitAmount = profitAmount,
            holdingQty = holdingQty,
            buyAttempt = BuyAttemptInfo(completed = cycle.buyAttempt),
        )
    }
}
