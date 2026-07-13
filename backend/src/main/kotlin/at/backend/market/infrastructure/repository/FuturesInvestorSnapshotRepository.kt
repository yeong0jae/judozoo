package at.backend.market.infrastructure.repository

import at.backend.market.domain.FuturesInvestorSnapshot
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalDateTime

interface FuturesInvestorSnapshotRepository : JpaRepository<FuturesInvestorSnapshot, Long> {

    /** 주어진 시각 이하 가장 가까운(최신) 스냅샷 — 세션 경계의 누적 순매수 조회용. */
    fun findFirstByTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
        tradeDate: LocalDate,
        capturedAt: LocalDateTime,
    ): FuturesInvestorSnapshot?

    /** 그날의 마지막 스냅샷 — 당일 누적("전체" 행)용. */
    fun findFirstByTradeDateOrderByCapturedAtDesc(tradeDate: LocalDate): FuturesInvestorSnapshot?

    /** 거래일별 마지막 스냅샷(= 그날의 당일 누적)을 최신 날짜순으로 — 일별 히스토리용. */
    @Query(
        """
        select s from FuturesInvestorSnapshot s
        where s.capturedAt = (
            select max(s2.capturedAt) from FuturesInvestorSnapshot s2 where s2.tradeDate = s.tradeDate
        )
        order by s.tradeDate desc
        """,
    )
    fun findDailyLatest(pageable: Pageable): List<FuturesInvestorSnapshot>
}
