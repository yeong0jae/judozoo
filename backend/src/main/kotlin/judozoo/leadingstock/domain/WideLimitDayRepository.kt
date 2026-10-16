package judozoo.leadingstock.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface WideLimitDayRepository : JpaRepository<WideLimitDayEntity, Int> {
    @Query("select w.stockCode from WideLimitDayEntity w where w.tradeDate = :day")
    fun codesOn(day: LocalDate): Set<String>

    @Modifying
    @Query("delete from WideLimitDayEntity w where w.tradeDate < :day")
    fun deleteBefore(day: LocalDate): Int
}
