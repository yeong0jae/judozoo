package judozoo.leadingstock.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SignalEventRepository : JpaRepository<SignalEvent, Int> {
    /** 그날 전이, 최신순. */
    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<SignalEvent>
}
