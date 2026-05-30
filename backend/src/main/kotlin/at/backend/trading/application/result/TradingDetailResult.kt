package at.backend.trading.application.result

import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderStatus
import java.math.BigDecimal
import java.time.LocalDateTime

data class TradingDetailResult(
    val cycleId: Long,
    val stockCode: String,
    val stockName: String,
    val status: String,
    val currentPrice: Long,
    val averageBuyPrice: Long,
    val profitRate: Double,
    val profitAmount: Long,
    val holdingQty: Int,
    val totalBoughtQty: Int,
    val tpStages: TpStagesInfo,
    val splitSellProgress: SplitSellProgressInfo,
    val breakevenArmed: Boolean,
    val trendBreakArmed: Boolean,
    val closeReason: String?,
    val createdAt: LocalDateTime,
    val closedAt: LocalDateTime?,
    val perBuyAmount: Long,
    val perBuyQty: Int?,
    val splitSellRatio: BigDecimal,
    val breakevenThresholdPct: BigDecimal,
    val stopLossPct: BigDecimal,
    val activeSell: Any? = null,
    val orders: List<OrderInfo>,
    val executions: List<ExecutionInfo>,
) {
    data class TpStagesInfo(val fired2pct: Boolean, val fired3pct: Boolean, val fired5pct: Boolean) {
        companion object {
            fun from(tpStagesFired: Int) = TpStagesInfo(
                fired2pct = tpStagesFired and 0b001 != 0,
                fired3pct = tpStagesFired and 0b010 != 0,
                fired5pct = tpStagesFired and 0b100 != 0,
            )
        }
    }

    data class SplitSellProgressInfo(val soldPct: Int)

    data class OrderInfo(
        val id: Long,
        val side: String,
        val trigger: String,
        val status: String,
        val orderQty: Int,
        val filledQty: Int,
        val submittedAt: LocalDateTime,
        val settledAt: LocalDateTime?,
        val retryCount: Int,
        val lastError: String?,
    ) {
        companion object {
            fun from(order: Order) = OrderInfo(
                id = order.id,
                side = order.side.name,
                trigger = order.trigger,
                status = order.status.name,
                orderQty = order.orderQty,
                filledQty = order.filledQty,
                submittedAt = order.createdAt,
                settledAt = order.updatedAt.takeIf {
                    order.status == OrderStatus.FILLED || order.status == OrderStatus.CANCELLED
                },
                retryCount = order.retryCount,
                lastError = order.lastError,
            )
        }
    }

    data class ExecutionInfo(
        val orderId: Long,
        val executedQty: Int,
        val executedPrice: Int,
        val fee: Int,
        val tax: Int,
        val executedAt: LocalDateTime,
    ) {
        companion object {
            fun from(execution: Execution) = ExecutionInfo(
                orderId = execution.orderId,
                executedQty = execution.executedQty,
                executedPrice = execution.executedPrice,
                fee = execution.fee,
                tax = execution.tax,
                executedAt = execution.createdAt,
            )
        }
    }

    companion object {
        fun from(
            cycle: TradingCycle,
            currentPrice: Long,
            holdingQty: Int,
            averageBuyPrice: Long,
            totalBoughtQty: Int,
            soldPct: Int,
            profitRate: Double,
            profitAmount: Long,
            orders: List<Order>,
            executions: List<Execution>,
        ) = TradingDetailResult(
            cycleId = cycle.id,
            stockCode = cycle.stockCode,
            stockName = cycle.stockName,
            status = cycle.status.name,
            currentPrice = currentPrice,
            averageBuyPrice = averageBuyPrice,
            profitRate = profitRate,
            profitAmount = profitAmount,
            holdingQty = holdingQty,
            totalBoughtQty = totalBoughtQty,
            tpStages = TpStagesInfo.from(cycle.tpStagesFired),
            splitSellProgress = SplitSellProgressInfo(soldPct = soldPct),
            breakevenArmed = cycle.breakevenArmed,
            trendBreakArmed = cycle.trendBreakArmed,
            closeReason = cycle.closeReason?.name,
            createdAt = cycle.createdAt,
            closedAt = cycle.closedAt,
            perBuyAmount = cycle.perBuyAmount,
            perBuyQty = cycle.perBuyQty,
            splitSellRatio = cycle.splitSellRatio,
            breakevenThresholdPct = cycle.breakevenThresholdPct,
            stopLossPct = cycle.stopLossPct.negate(),
            activeSell = null,
            orders = orders.map { OrderInfo.from(it) },
            executions = executions.map { ExecutionInfo.from(it) },
        )
    }
}
