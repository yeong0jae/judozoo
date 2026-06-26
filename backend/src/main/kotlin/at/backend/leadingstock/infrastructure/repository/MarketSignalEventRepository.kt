package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.domain.MarketSignalType
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
}
