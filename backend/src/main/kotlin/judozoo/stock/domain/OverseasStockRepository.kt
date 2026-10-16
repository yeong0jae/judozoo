package judozoo.stock.domain

import org.springframework.data.jpa.repository.JpaRepository

interface OverseasStockRepository : JpaRepository<OverseasStock, Int> {
    fun findFirstByOrderByCreatedAtDesc(): OverseasStock?
}
