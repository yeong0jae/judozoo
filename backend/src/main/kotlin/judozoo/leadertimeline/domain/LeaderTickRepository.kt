package judozoo.leadertimeline.domain

import judozoo.market.domain.Region
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface LeaderTickRepository : JpaRepository<LeaderTick, Long> {
    fun findByRegionAndAt(region: Region, at: LocalDateTime): List<LeaderTick>

    fun findByRegionAndTradeDateOrderByAt(region: Region, tradeDate: LocalDate): List<LeaderTick>

    fun findByRegionAndTradeDateAndAtAfterOrderByAt(region: Region, tradeDate: LocalDate, after: LocalDateTime): List<LeaderTick>

    fun findFirstByRegionAndTradeDateOrderByAtDesc(region: Region, tradeDate: LocalDate): LeaderTick?
}
