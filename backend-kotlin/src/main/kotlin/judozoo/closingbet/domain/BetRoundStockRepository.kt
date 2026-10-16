package judozoo.closingbet.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface BetRoundStockRepository : JpaRepository<BetRoundStock, Long> {
    fun findByTradingDay(tradingDay: LocalDate): List<BetRoundStock>
}
