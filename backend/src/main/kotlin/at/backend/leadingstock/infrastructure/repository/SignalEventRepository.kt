package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.SignalEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SignalEventRepository : JpaRepository<SignalEvent, Long> {

    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<SignalEvent>
}
