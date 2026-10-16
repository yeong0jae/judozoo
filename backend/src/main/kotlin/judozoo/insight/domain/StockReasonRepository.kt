package judozoo.insight.domain

import judozoo.market.domain.Region
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface StockReasonRepository : JpaRepository<StockReason, Long> {
    fun findByRegionAndTradingDay(region: Region, tradingDay: LocalDate): List<StockReason>

    fun countByGeneratedAtGreaterThanEqual(since: LocalDateTime): Long
}
