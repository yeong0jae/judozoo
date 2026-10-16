package judozoo.leadingstock.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface StockMinuteCandleRepository : JpaRepository<StockMinuteCandleEntity, Int> {
    fun findByStockCodeAndTradeDateOrderByMinuteAt(stockCode: String, tradeDate: LocalDate): List<StockMinuteCandleEntity>

    @Modifying
    @Query("delete from StockMinuteCandleEntity c where c.stockCode = :stockCode and c.tradeDate = :day")
    fun deleteDay(stockCode: String, day: LocalDate): Int

    @Modifying
    @Query("delete from StockMinuteCandleEntity c where c.tradeDate < :day")
    fun deleteBefore(day: LocalDate): Int
}
