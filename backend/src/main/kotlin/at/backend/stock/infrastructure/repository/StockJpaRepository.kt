package at.backend.stock.infrastructure.repository

import at.backend.stock.domain.Stock
import org.springframework.data.jpa.repository.JpaRepository

interface StockJpaRepository : JpaRepository<Stock, String> {
    fun findTopByOrderByCreatedAtDesc(): Stock?
}
