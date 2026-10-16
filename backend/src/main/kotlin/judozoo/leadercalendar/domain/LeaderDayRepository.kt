package judozoo.leadercalendar.domain

import judozoo.market.domain.Region
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface LeaderDayRepository : JpaRepository<LeaderDay, Long> {
    fun findByRegionAndTradeDate(region: Region, tradeDate: LocalDate): List<LeaderDay>

    fun findByRegionAndTradeDateGreaterThanEqualAndTradeDateLessThanOrderByTradeDate(region: Region, from: LocalDate, until: LocalDate): List<LeaderDay>
}
