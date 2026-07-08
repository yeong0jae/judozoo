package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.MarketInvestorSnapshot
import at.backend.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface MarketInvestorSnapshotRepository : JpaRepository<MarketInvestorSnapshot, Long> {

    /** 한 시장의, 주어진 시각 이하 가장 가까운(최신) 스냅샷 — 시그널 발생 시점의 순매수 조회용. */
    fun findFirstByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
        market: Market,
        tradeDate: LocalDate,
        capturedAt: LocalDateTime,
    ): MarketInvestorSnapshot?
}
