package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.InvestorType
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.domain.MarketSignalType
import at.backend.leadingstock.domain.NetTradeSide
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketSignalEventRepository : JpaRepository<MarketSignalEvent, Long> {

    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<MarketSignalEvent>

    /** 그날 한 시장의 최신 시그널 1건 — 캔들 디바운스 상태 복원용. */
    fun findFirstByMarketAndKindAndTradeDateOrderByOccurredAtDesc(
        market: Market,
        kind: MarketSignalType,
        tradeDate: LocalDate,
    ): MarketSignalEvent?

    /** 그날 같은 (시장·투자자·방향·단계) 순매수 시그널이 이미 적재됐는지 — 재시작/회복 시 같은 단계 재발화 방지. */
    fun existsByTradeDateAndKindAndMarketAndInvestorAndSideAndLevel(
        tradeDate: LocalDate,
        kind: MarketSignalType,
        market: Market,
        investor: InvestorType,
        side: NetTradeSide,
        level: Int,
    ): Boolean
}
