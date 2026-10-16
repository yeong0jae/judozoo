package judozoo.leadercalendar.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface LeaderDayStockRepository : JpaRepository<LeaderDayStock, Long> {
    fun findByLeaderDayIdInOrderByRank(dayIds: Collection<Long>): List<LeaderDayStock>

    @Modifying
    @Query("delete from LeaderDayStock s where s.leaderDayId in :dayIds")
    fun deleteByDayIds(dayIds: Collection<Long>): Int
}
