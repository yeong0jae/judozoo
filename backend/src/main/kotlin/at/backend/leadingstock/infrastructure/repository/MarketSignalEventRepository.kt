package at.backend.leadingstock.infrastructure.repository

import at.backend.leadingstock.domain.MarketSignalEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MarketSignalEventRepository : JpaRepository<MarketSignalEvent, Long> {

    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<MarketSignalEvent>
}
