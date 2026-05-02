package at.backend.trading.infrastructure.repository

import at.backend.trading.domain.TradingCycle
import at.backend.trading.domain.TradingCycleStatus
import org.springframework.data.jpa.repository.JpaRepository

interface TradingCycleJpaRepository : JpaRepository<TradingCycle, Long> {
    fun findByStatusIn(statuses: List<TradingCycleStatus>): List<TradingCycle>
    fun findByStockCodeAndStatusIn(stockCode: String, statuses: List<TradingCycleStatus>): List<TradingCycle>
}
