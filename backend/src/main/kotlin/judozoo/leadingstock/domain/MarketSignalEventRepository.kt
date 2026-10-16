package judozoo.leadingstock.domain

import judozoo.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketSignalEventRepository : JpaRepository<MarketSignalEvent, Int> {
    /** 그날 전이, 최신순. */
    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<MarketSignalEvent>

    /** 그날 같은 (종류·시장·투자자·방향·단계) 시그널이 이미 적재됐는지 — 재시작이나 값 회복으로 같은 단계가 다시 울리는 걸 막는다. */
    fun existsByTradeDateAndKindAndMarketAndInvestorAndSideAndLevel(
        tradeDate: LocalDate, kind: String, market: Market, investor: InvestorType, side: NetTradeSide, level: Int,
    ): Boolean
}
