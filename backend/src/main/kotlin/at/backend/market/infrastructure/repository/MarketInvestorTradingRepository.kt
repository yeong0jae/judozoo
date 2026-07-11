package at.backend.market.infrastructure.repository

import at.backend.market.domain.MarketInvestorTrading
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface MarketInvestorTradingRepository : JpaRepository<MarketInvestorTrading, Long> {

    /** 한 시장의 그날 스냅샷 전체(시각 오름차순) — 장중 시계열/갱신주기 확인용. */
    fun findByMarketAndTradeDateOrderByCapturedAtAsc(
        market: Market,
        tradeDate: LocalDate,
    ): List<MarketInvestorTrading>

    /** 주어진 시각 이하 가장 가까운(최신) 스냅샷 — 세션 경계 값 조회용. */
    fun findFirstByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
        market: Market,
        tradeDate: LocalDate,
        capturedAt: LocalDateTime,
    ): MarketInvestorTrading?
}
