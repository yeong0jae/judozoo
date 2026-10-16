package judozoo.insight.domain

import judozoo.market.domain.Region
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface StockReasonRepository : JpaRepository<StockReason, Long> {
    fun findByRegionAndTradingDay(region: Region, tradingDay: LocalDate): List<StockReason>

    /** 하루 상한은 청구서 기준이라 시장을 나누지 않는다. */
    fun countByGeneratedAtGreaterThanEqual(since: LocalDateTime): Long
}
