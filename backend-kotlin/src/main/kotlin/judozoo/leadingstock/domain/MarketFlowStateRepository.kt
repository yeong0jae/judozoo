package judozoo.leadingstock.domain

import judozoo.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketFlowStateRepository : JpaRepository<MarketFlowState, Int> {
    fun findByTradeDate(tradeDate: LocalDate): List<MarketFlowState>

    fun findByMarketAndInvestor(market: Market, investor: InvestorType): MarketFlowState?
}
