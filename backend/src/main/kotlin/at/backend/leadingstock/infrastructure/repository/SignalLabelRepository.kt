package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.SignalLabel
import org.springframework.data.jpa.repository.JpaRepository

interface SignalLabelRepository : JpaRepository<SignalLabel, Long> {

    fun findBySignalEventIdIn(signalEventIds: Collection<Long>): List<SignalLabel>
}
