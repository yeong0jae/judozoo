package judozoo.leadingstock.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SignalEventRepository : JpaRepository<SignalEvent, Int> {
    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<SignalEvent>
}
