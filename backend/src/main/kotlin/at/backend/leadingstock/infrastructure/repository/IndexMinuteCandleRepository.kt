package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.IndexMinuteCandleEntity
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface IndexMinuteCandleRepository : JpaRepository<IndexMinuteCandleEntity, Long> {

    fun findByMarketAndTradeDateOrderByMinuteAsc(market: Market, tradeDate: LocalDate): List<IndexMinuteCandleEntity>

    fun findByMarketAndTradeDateBetweenOrderByMinuteAsc(
        market: Market,
        from: LocalDate,
        to: LocalDate,
    ): List<IndexMinuteCandleEntity>

    fun findByMarketAndMinute(market: Market, minute: LocalDateTime): IndexMinuteCandleEntity?
}
