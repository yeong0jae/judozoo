package at.backend.report.application

import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class ReportService(
    private val cycleRepository: TradingCycleJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
) {

    fun findDaily(date: LocalDate): List<DailyReportResult> {
        val startOfDay = date.atStartOfDay()
        val endOfDay = startOfDay.plusDays(1)
        return cycleRepository
            .findByCreatedAtBetween(startOfDay, endOfDay)
            .map { aggregate(it) }
    }

    private fun aggregate(cycle: TradingCycle): DailyReportResult {
        val orders = orderRepository.findByCycleId(cycle.id)
        val executions = orders.flatMap { executionRepository.findByOrderId(it.id) }
        val orderById = orders.associateBy { it.id }

        val (buyExecs, sellExecs) = executions.partition {
            orderById[it.orderId]?.side == "BUY"
        }

        val totalBoughtAmount = buyExecs.sumOf { it.executedPrice.toLong() * it.executedQty }
        val totalSoldAmount = sellExecs.sumOf { it.executedPrice.toLong() * it.executedQty }
        val totalBoughtQty = buyExecs.sumOf { it.executedQty }
        val totalSoldQty = sellExecs.sumOf { it.executedQty }

        val totalFee = (buyExecs + sellExecs).sumOf { it.fee.toLong() }
        val totalTax = (buyExecs + sellExecs).sumOf { it.tax.toLong() }

        val grossProfit = totalSoldAmount - totalBoughtAmount
        val netProfit = grossProfit - totalFee - totalTax
        val profitRate =
            if (totalBoughtAmount == 0L) 0.0
            else netProfit.toDouble() / totalBoughtAmount

        return DailyReportResult(
            commandId = cycle.id,
            stockCode = cycle.stockCode,
            stockName = cycle.stockName,
            status = cycle.status.name,
            closeReason = cycle.closeReason?.name,
            createdAt = cycle.createdAt,
            closedAt = cycle.closedAt,
            avgBuyPrice = avgPrice(totalBoughtAmount, totalBoughtQty),
            avgSellPrice = avgPrice(totalSoldAmount, totalSoldQty),
            totalFee = totalFee,
            totalTax = totalTax,
            grossProfit = grossProfit,
            netProfit = netProfit,
            profitRate = profitRate,
        )
    }

    private fun avgPrice(amount: Long, qty: Int): Long? =
        if (qty == 0) null else amount / qty
}
