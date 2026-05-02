package at.backend.trading.infrastructure.repository

import at.backend.trading.domain.Order
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByCycleId(cycleId: Long): List<Order>
}
