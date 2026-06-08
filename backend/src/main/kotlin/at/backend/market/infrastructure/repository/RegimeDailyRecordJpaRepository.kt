package at.backend.market.infrastructure.repository

import at.backend.market.domain.regime.RegimeDailyRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RegimeDailyRecordJpaRepository : JpaRepository<RegimeDailyRecord, LocalDate> {
    /** 최근 20일 (최신순). */
    fun findTop20ByOrderByDateDesc(): List<RegimeDailyRecord>

    /** 직전 거래일 기록 — 오전 NXT를 전일 20:00 기준으로 보정할 때 사용. */
    fun findTopByDateBeforeOrderByDateDesc(date: LocalDate): RegimeDailyRecord?
}
