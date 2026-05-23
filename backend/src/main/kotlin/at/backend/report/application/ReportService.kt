package at.backend.report.application

import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.OrderSide
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
    private val broker: BrokerTradingClient,
) {

    fun findDaily(date: LocalDate): List<DailyReportResult> {
        val startOfDay = date.atStartOfDay()
        val endOfDay = startOfDay.plusDays(1)
        return cycleRepository
            .findByAccountNoAndCreatedAtBetween(broker.accountNo, startOfDay, endOfDay)
            .map { aggregate(it) }
    }

    private fun aggregate(cycle: TradingCycle): DailyReportResult {
        val orders = orderRepository.findByCycleId(cycle.id)
        val executions = orders.flatMap { executionRepository.findByOrderId(it.id) }
        val orderById = orders.associateBy { it.id }

        val (buyExecutions, sellExecutions) = executions.partition {
            orderById[it.orderId]?.side == OrderSide.BUY
        }

        val totalBoughtAmount = buyExecutions.sumOf { it.executedPrice.toLong() * it.executedQty }
        val totalSoldAmount = sellExecutions.sumOf { it.executedPrice.toLong() * it.executedQty }
        val totalBoughtQty = buyExecutions.sumOf { it.executedQty }
        val totalSoldQty = sellExecutions.sumOf { it.executedQty }

        val totalFee = (buyExecutions + sellExecutions).sumOf { it.fee.toLong() }
        val totalTax = (buyExecutions + sellExecutions).sumOf { it.tax.toLong() }

        // 손익 확정으로 보지 않는 사이클은 null 처리한다 — 합계/승패에서 자동 제외.
        //   1) CLOSED가 아닌 사이클: 아직 진행 중
        //   2) closeReason == UNCLOSED: KIS 측에서 청산이 끝나지 않은 비정상 종료
        val realized = cycle.status == TradingCycleStatus.CLOSED && cycle.closeReason != CloseReason.UNCLOSED
        val grossProfit: Long? = if (realized) totalSoldAmount - totalBoughtAmount else null
        val netProfit: Long? = grossProfit?.let { it - totalFee - totalTax }
        val profitRate: Double? = when {
            netProfit == null -> null
            totalBoughtAmount == 0L -> 0.0
            else -> netProfit.toDouble() / totalBoughtAmount
        }

        return DailyReportResult(
            cycleId = cycle.id,
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
