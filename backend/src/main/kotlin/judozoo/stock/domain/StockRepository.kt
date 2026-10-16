package judozoo.stock.domain

import org.springframework.data.jpa.repository.JpaRepository

interface StockRepository : JpaRepository<Stock, Long> {
    fun findFirstByOrderByCreatedAtDesc(): Stock?
}
