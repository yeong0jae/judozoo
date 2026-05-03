package at.backend.trading.infrastructure.repository

import at.backend.trading.domain.execution.Execution
import org.springframework.data.jpa.repository.JpaRepository

interface ExecutionJpaRepository : JpaRepository<Execution, Long> {
    fun findByOrderId(orderId: Long): List<Execution>
}
