package at.backend.trading.infrastructure.repository

import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface TradingCycleJpaRepository : JpaRepository<TradingCycle, Long> {
    fun findByAccountNoAndStatusIn(accountNo: String, statuses: List<TradingCycleStatus>): List<TradingCycle>
    fun findByAccountNoAndStockCodeAndStatusIn(
        accountNo: String,
        stockCode: String,
        statuses: List<TradingCycleStatus>,
    ): List<TradingCycle>
    fun findByAccountNoAndCreatedAtBetween(
        accountNo: String,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<TradingCycle>
}
