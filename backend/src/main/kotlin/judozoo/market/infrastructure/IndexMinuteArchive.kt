package judozoo.market.infrastructure

import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class IndexMinuteArchive(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(market: Market, day: LocalDate): List<TossCandle>? = try {
        jdbc.query("SELECT candles FROM index_minute_day WHERE market = ? AND trade_date = ?", { rs, _ -> rs.getString(1) }, market.name, day)
            .firstOrNull()?.let(MinuteDayJson::tossCandles)
    } catch (e: Exception) {
        log.warn("지수 분봉 DB 조회 실패 market={} day={}", market, day, e)
        null
    }

    fun put(market: Market, day: LocalDate, bars: List<TossCandle>) {
        if (bars.isEmpty()) return
        try {
            jdbc.update(
                "INSERT INTO index_minute_day (market, trade_date, candles) VALUES (?, ?, ?) AS new ON DUPLICATE KEY UPDATE candles = new.candles",
                market.name, day, MinuteDayJson.tossCandles(bars),
            )
        } catch (e: Exception) {
            log.warn("지수 분봉 DB 저장 실패 market={} day={}", market, day, e)
        }
    }
}
