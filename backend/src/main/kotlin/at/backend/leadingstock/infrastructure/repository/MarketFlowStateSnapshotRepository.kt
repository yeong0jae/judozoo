package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.InvestorType
import at.backend.leadingstock.domain.MarketFlowStateSnapshot
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketFlowStateSnapshotRepository : JpaRepository<MarketFlowStateSnapshot, Long> {

    fun findByTradeDate(tradeDate: LocalDate): List<MarketFlowStateSnapshot>

    fun findByMarketAndInvestor(market: Market, investor: InvestorType): MarketFlowStateSnapshot?
}
