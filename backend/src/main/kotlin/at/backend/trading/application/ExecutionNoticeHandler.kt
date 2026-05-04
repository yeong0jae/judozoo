package at.backend.trading.application

import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ExecutionNoticeHandler(
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(notice: ExecutionNotice) {
        val order = orderRepository.findByKisOrderNo(notice.kisOrderNo)
        if (order == null) {
            log.debug("일치하는 Order 없음 — 통보 무시 kisOrderNo={}", notice.kisOrderNo)
            return
        }
        val execution = order.applyExecution(notice, fee = 0, tax = 0)
        orderRepository.save(order)
        executionRepository.save(execution)
    }
}
