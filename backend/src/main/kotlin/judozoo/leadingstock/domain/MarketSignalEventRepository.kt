package judozoo.leadingstock.domain

import judozoo.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketSignalEventRepository : JpaRepository<MarketSignalEvent, Int> {
    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<MarketSignalEvent>

    fun existsByTradeDateAndKindAndMarketAndInvestorAndSideAndLevel(
        tradeDate: LocalDate, kind: MarketSignalType, market: Market, investor: InvestorType, side: NetTradeSide, level: Int,
    ): Boolean
}
