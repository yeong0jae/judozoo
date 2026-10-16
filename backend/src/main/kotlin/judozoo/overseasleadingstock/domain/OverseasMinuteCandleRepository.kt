package judozoo.overseasleadingstock.domain

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface OverseasMinuteCandleRepository : JpaRepository<OverseasMinuteCandleEntity, Int> {
    fun findByExchangeAndSymbolAndTradingDayOrderByMinuteAt(exchange: String, symbol: String, tradingDay: LocalDate): List<OverseasMinuteCandleEntity>

    @Query(
        "select distinct c.tradingDay from OverseasMinuteCandleEntity c " +
            "where c.exchange = :exchange and c.symbol = :symbol order by c.tradingDay desc",
    )
    fun latestDays(exchange: String, symbol: String, limit: Limit): List<LocalDate>

    @Modifying
    @Query("delete from OverseasMinuteCandleEntity c where c.exchange = :exchange and c.symbol = :symbol and c.tradingDay = :day")
    fun deleteDay(exchange: String, symbol: String, day: LocalDate): Int

    @Modifying
    @Query("delete from OverseasMinuteCandleEntity c where c.tradingDay < :day")
    fun deleteBefore(day: LocalDate): Int
}
