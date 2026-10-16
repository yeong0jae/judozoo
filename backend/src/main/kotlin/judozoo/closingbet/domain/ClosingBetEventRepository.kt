package judozoo.closingbet.domain

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface ClosingBetEventRepository : JpaRepository<ClosingBetEvent, Long> {
    fun findByTradingDayAndIdGreaterThanOrderByIdDesc(tradingDay: LocalDate, afterId: Long, limit: Limit): List<ClosingBetEvent>
}
