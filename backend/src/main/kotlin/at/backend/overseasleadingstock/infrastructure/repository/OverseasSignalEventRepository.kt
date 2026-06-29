package at.backend.overseasleadingstock.infrastructure.repository

import at.backend.overseasleadingstock.domain.OverseasSignalEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface OverseasSignalEventRepository : JpaRepository<OverseasSignalEvent, Long> {

    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<OverseasSignalEvent>
}
