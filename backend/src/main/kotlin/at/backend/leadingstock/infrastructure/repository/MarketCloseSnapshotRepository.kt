package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.MarketCloseSnapshot
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketCloseSnapshotRepository : JpaRepository<MarketCloseSnapshot, Long> {

    fun findByTradeDate(tradeDate: LocalDate): List<MarketCloseSnapshot>

    fun existsByMarketAndTradeDate(market: Market, tradeDate: LocalDate): Boolean
}
