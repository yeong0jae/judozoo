package at.backend.market.infrastructure.repository

import at.backend.market.domain.regime.RegimeDailyRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RegimeDailyRecordJpaRepository : JpaRepository<RegimeDailyRecord, LocalDate> {
    /** 최근 10일 (최신순). */
    fun findTop10ByOrderByDateDesc(): List<RegimeDailyRecord>
}
