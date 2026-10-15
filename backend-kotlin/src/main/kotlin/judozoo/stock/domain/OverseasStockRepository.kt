package judozoo.stock.domain

import org.springframework.data.jpa.repository.JpaRepository

/** 읽기만 한다 — 공존하는 동안 카탈로그 갱신은 Python이 한다(028). */
interface OverseasStockRepository : JpaRepository<OverseasStock, Int>
