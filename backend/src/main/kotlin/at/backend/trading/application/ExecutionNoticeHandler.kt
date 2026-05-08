package at.backend.trading.application

import at.backend.trading.domain.event.OrderExecuted
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ExecutionNoticeHandler(
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(notice: ExecutionNotice) {
        val order = orderRepository.findByOrderNo(notice.orderNo)
        if (order == null) {
            log.debug("일치하는 Order 없음 — 통보 무시 orderNo={}", notice.orderNo)
            return
        }
        val execution = order.applyExecution(notice, fee = 0, tax = 0)
        orderRepository.save(order)
        executionRepository.save(execution)
        publishExecuted(order.cycleId, notice)
    }

    private fun publishExecuted(cycleId: Long, notice: ExecutionNotice) {
        val orders = orderRepository.findByCycleId(cycleId)
        val totalFilled = orders.filter { it.side == OrderSide.BUY }.sumOf { it.filledQty }
        val sold = orders.filter { it.side == OrderSide.SELL }.sumOf { it.filledQty }
        val holdingQty = (totalFilled - sold).coerceAtLeast(0)
        val buyExecutions = orders.filter { it.side == OrderSide.BUY }
            .flatMap { executionRepository.findByOrderId(it.id) }
        val averageBuyPrice = if (buyExecutions.isNotEmpty()) {
            val cost = buyExecutions.sumOf { it.executedPrice.toLong() * it.executedQty }
            val qty = buyExecutions.sumOf { it.executedQty }
            (cost / qty).toInt()
        } else 0

        eventPublisher.publishEvent(
            OrderExecuted(
                cycleId = cycleId,
                side = notice.side.name,
                qty = notice.executedQty,
                price = notice.executedPrice,
                totalFilledQty = totalFilled,
                holdingQty = holdingQty,
                averageBuyPrice = averageBuyPrice,
                ts = notice.timestamp,
            )
        )
    }
}
