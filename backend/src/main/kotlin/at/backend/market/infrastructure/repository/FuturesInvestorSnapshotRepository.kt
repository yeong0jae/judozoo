package at.backend.market.infrastructure.repository

import at.backend.market.domain.FuturesInvestorSnapshot
import at.backend.stock.domain.Market
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime

interface FuturesInvestorSnapshotRepository : JpaRepository<FuturesInvestorSnapshot, Long> {

    /** [market]의 주어진 시각 이하 가장 가까운(최신) 스냅샷 — 세션 경계의 누적 순매수 조회용. */
    fun findFirstByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
        market: Market,
        tradeDate: LocalDate,
        capturedAt: LocalDateTime,
    ): FuturesInvestorSnapshot?

    /** [market]의 거래일별 마지막 스냅샷(= 그날의 당일 누적)을 최신 날짜순으로 — 일별 히스토리용. */
    @Query(
        """
        select s from FuturesInvestorSnapshot s
        where s.market = :market and s.capturedAt = (
            select max(s2.capturedAt) from FuturesInvestorSnapshot s2
            where s2.market = :market and s2.tradeDate = s.tradeDate
        )
        order by s.tradeDate desc
        """,
    )
    fun findDailyLatest(@Param("market") market: Market, pageable: Pageable): List<FuturesInvestorSnapshot>
}
