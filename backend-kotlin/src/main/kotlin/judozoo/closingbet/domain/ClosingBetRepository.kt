package judozoo.closingbet.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface ClosingBetRepository : JpaRepository<ClosingBet, Long> {
    fun findByUserIdAndTradingDay(userId: Long, tradingDay: LocalDate): ClosingBet?

    fun findByTradingDay(tradingDay: LocalDate): List<ClosingBet>

    fun findByTradingDayAndStatus(tradingDay: LocalDate, status: String): List<ClosingBet>

    fun findByTradingDayAndStockCodeAndStatus(tradingDay: LocalDate, stockCode: String, status: String): List<ClosingBet>
}
