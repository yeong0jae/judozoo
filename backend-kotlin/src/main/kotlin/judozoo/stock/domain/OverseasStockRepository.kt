package judozoo.stock.domain

import org.springframework.data.jpa.repository.JpaRepository

interface OverseasStockRepository : JpaRepository<OverseasStock, Int> {
    /** 가장 최근 행 — 그 생성일이 마지막 갱신일이다. */
    fun findFirstByOrderByCreatedAtDesc(): OverseasStock?
}
