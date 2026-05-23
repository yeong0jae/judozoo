package at.backend.trading.application

import at.backend.library.exception.EntityNotFoundException
import at.backend.library.time.TimeProvider
import at.backend.trading.TradingProperties
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.result.DailyTradingResult
import at.backend.trading.application.result.TradingDetailResult
import at.backend.trading.application.result.TradingSummaryResult
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.springframework.stereotype.Service

@Service
class TradingQueryService(
    private val tradingCycleRepository: TradingCycleJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
    private val broker: BrokerTradingClient,
    private val tradingProperties: TradingProperties,
    private val timeProvider: TimeProvider,
) {

    fun findActive(): List<TradingSummaryResult> =
        tradingCycleRepository.findByAccountNoAndStatusIn(broker.accountNo, TradingCycleStatus.OPEN)
            .map { toSummary(it) }

    fun findToday(): List<DailyTradingResult> {
        val startOfDay = timeProvider.today().atStartOfDay()
        val endOfDay = startOfDay.plusDays(1)
        return tradingCycleRepository.findByAccountNoAndCreatedAtBetween(broker.accountNo, startOfDay, endOfDay)
            .filter { it.status == TradingCycleStatus.CLOSED }   // "오늘 종료" 미니 섹션 — 진행 중 사이클은 위쪽 활성 명령에 노출됨
            .map { toDailyTrading(it) }
    }

    fun findById(id: Long): TradingDetailResult {
        val cycle = tradingCycleRepository.findById(id)
            .orElseThrow { EntityNotFoundException("TradingCycle not found: $id") }
        return toDetail(cycle)
    }

    private fun toSummary(cycle: TradingCycle): TradingSummaryResult {
        val (orders, executions) = loadOrdersAndExecutions(cycle.id)
        val holdingInfo = computeHolding(cycle, orders, executions)

        val currentPrice = fetchCurrentPrice(cycle.stockCode)
        return TradingSummaryResult.from(
            cycle = cycle,
            currentPrice = currentPrice,
            holdingQty = holdingInfo.holdingQty,
            averageBuyPrice = holdingInfo.averageBuyPrice,
            profitRate = profitRate(currentPrice, holdingInfo.averageBuyPrice),
            profitAmount = profitAmount(currentPrice, holdingInfo.averageBuyPrice, holdingInfo.holdingQty),
        )
    }

    private fun toDailyTrading(cycle: TradingCycle): DailyTradingResult {
        val (orders, executions) = loadOrdersAndExecutions(cycle.id)

        val totalSoldAmount = orders
            .filter { it.side == OrderSide.SELL }
            .flatMap { o -> executions.filter { it.orderId == o.id } }
            .sumOf { it.executedPrice.toLong() * it.executedQty }
        val totalBuyAmount = orders
            .filter { it.side == OrderSide.BUY }
            .flatMap { o -> executions.filter { it.orderId == o.id } }
            .sumOf { it.executedPrice.toLong() * it.executedQty }

        // 손익으로 보지 않는 사이클은 null — 헤더 합계 및 메인 화면에서 자동 제외.
        //   - CLOSED가 아닌 경우(여기 도달하지 않음) 또는 closeReason == UNCLOSED(비정상 종료)
        val realized = cycle.status == TradingCycleStatus.CLOSED && cycle.closeReason != CloseReason.UNCLOSED
        val profitAmount: Long? = if (realized) totalSoldAmount - totalBuyAmount else null
        val profitRate: Double? = when {
            profitAmount == null -> null
            totalBuyAmount == 0L -> 0.0
            else -> profitAmount.toDouble() / totalBuyAmount
        }

        return DailyTradingResult.from(cycle, profitRate, profitAmount)
    }

    private fun toDetail(cycle: TradingCycle): TradingDetailResult {
        val (orders, executions) = loadOrdersAndExecutions(cycle.id)

        val currentPrice = fetchCurrentPrice(cycle.stockCode)

        val (holdingQty, averageBuyPrice, totalBoughtQty) = computeHolding(cycle, orders, executions)

        val totalSoldQty = orders
            .filter { it.side == OrderSide.SELL }
            .flatMap { o -> executions.filter { it.orderId == o.id } }
            .sumOf { it.executedQty }
        val soldPct = if (totalBoughtQty == 0) 0 else totalSoldQty * 100 / totalBoughtQty
        
        return TradingDetailResult.from(
            cycle = cycle,
            currentPrice = currentPrice,
            holdingQty = holdingQty,
            averageBuyPrice = averageBuyPrice,
            totalBoughtQty = totalBoughtQty,
            soldPct = soldPct,
            profitRate = profitRate(currentPrice, averageBuyPrice),
            profitAmount = profitAmount(currentPrice, averageBuyPrice, holdingQty),
            orders = orders,
            executions = executions,
        )
    }

    private fun loadOrdersAndExecutions(cycleId: Long): Pair<List<Order>, List<Execution>> {
        val orders = orderRepository.findByCycleId(cycleId)
        val executions = orders.flatMap { executionRepository.findByOrderId(it.id) }
        return orders to executions
    }

    private data class HoldingInfo(val holdingQty: Int, val averageBuyPrice: Long, val totalBoughtQty: Int)

    private fun computeHolding(cycle: TradingCycle, orders: List<Order>, executions: List<Execution>): HoldingInfo {
        val buyExecutions = orders
            .filter { it.side == OrderSide.BUY }
            .flatMap { o -> executions.filter { it.orderId == o.id } }
        val sellExecutions = orders
            .filter { it.side == OrderSide.SELL }
            .flatMap { o -> executions.filter { it.orderId == o.id } }

        val totalBoughtQty = buyExecutions.sumOf { it.executedQty }
        val totalSoldQty = sellExecutions.sumOf { it.executedQty }
        val holdingQty = totalBoughtQty - totalSoldQty

        val averageBuyPrice = if (buyExecutions.isEmpty()) 0L
        else cycle.calculateBuyPrice(buyExecutions, tradingProperties.sellCostRate.toDouble()).toLong()

        return HoldingInfo(holdingQty, averageBuyPrice, totalBoughtQty)
    }

    private fun fetchCurrentPrice(stockCode: String): Long =
        broker.currentPrice(stockCode)

    private fun profitRate(currentPrice: Long, averageBuyPrice: Long): Double =
        if (averageBuyPrice == 0L) 0.0
        else (currentPrice - averageBuyPrice).toDouble() / averageBuyPrice

    private fun profitAmount(currentPrice: Long, averageBuyPrice: Long, holdingQty: Int): Long =
        (currentPrice - averageBuyPrice) * holdingQty
}
