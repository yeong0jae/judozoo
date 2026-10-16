package judozoo.leadingstock.domain

import judozoo.stock.domain.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface IndexMinuteCandleRepository : JpaRepository<IndexMinuteCandleEntity, Int> {
    fun findByMarketAndTradeDateBetweenOrderByMinuteAt(market: Market, from: LocalDate, to: LocalDate): List<IndexMinuteCandleEntity>

    fun findByMarketAndMinuteAtIn(market: Market, minutes: Collection<LocalDateTime>): List<IndexMinuteCandleEntity>
}
