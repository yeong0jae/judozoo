package at.backend.stock.infrastructure.repository

import at.backend.stock.domain.OverseasStock
import org.springframework.data.jpa.repository.JpaRepository

interface OverseasStockJpaRepository : JpaRepository<OverseasStock, Long> {
    fun findTopByOrderByCreatedAtDesc(): OverseasStock?
}
