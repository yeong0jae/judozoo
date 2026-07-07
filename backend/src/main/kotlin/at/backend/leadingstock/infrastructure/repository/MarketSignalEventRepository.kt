package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.InvestorType
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.domain.MarketSignalType
import at.backend.leadingstock.domain.NetTradeSide
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MarketSignalEventRepository : JpaRepository<MarketSignalEvent, Long> {

    /**
     * 그날 표시용 시그널 목록. 폐기된 'CANDLE_STREAK' 과거 행은 제외한다
     * (enum에서 상수를 없앤 뒤라 그대로 읽으면 매핑이 깨진다).
     */
    @Query(
        value = "SELECT * FROM market_signal_event WHERE trade_date = :date AND kind <> 'CANDLE_STREAK' ORDER BY occurred_at DESC",
        nativeQuery = true,
    )
    fun findByTradeDateOrderByOccurredAtDesc(@Param("date") tradeDate: LocalDate): List<MarketSignalEvent>

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
