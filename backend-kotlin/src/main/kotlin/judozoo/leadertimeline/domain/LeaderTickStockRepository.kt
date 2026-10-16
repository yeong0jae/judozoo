package judozoo.leadertimeline.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface LeaderTickStockRepository : JpaRepository<LeaderTickStock, Long> {
    fun findByLeaderTickIdInOrderByRank(tickIds: Collection<Long>): List<LeaderTickStock>

    @Modifying
    @Query("delete from LeaderTickStock s where s.leaderTickId in :tickIds")
    fun deleteByTickIds(tickIds: Collection<Long>): Int
}
