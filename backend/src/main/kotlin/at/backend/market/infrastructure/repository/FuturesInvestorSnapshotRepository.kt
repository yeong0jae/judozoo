package at.backend.market.infrastructure.repository

import at.backend.market.domain.FuturesInvestorSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface FuturesInvestorSnapshotRepository : JpaRepository<FuturesInvestorSnapshot, Long> {

    /** 주어진 시각 이하 가장 가까운(최신) 스냅샷 — 세션 경계의 누적 순매수 조회용. */
    fun findFirstByTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
        tradeDate: LocalDate,
        capturedAt: LocalDateTime,
    ): FuturesInvestorSnapshot?
}
