package at.backend.trading.infrastructure.repository

import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface TradingCycleJpaRepository : JpaRepository<TradingCycle, Long> {
    fun findByStatusIn(statuses: List<TradingCycleStatus>): List<TradingCycle>
    fun findByStockCodeAndStatusIn(stockCode: String, statuses: List<TradingCycleStatus>): List<TradingCycle>
    fun findByCreatedAtBetween(start: LocalDateTime, end: LocalDateTime): List<TradingCycle>
}
