package judozoo.market.infrastructure

import judozoo.platform.yahoo.YahooClient.YahooBar
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class YahooMinuteArchive(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recent(symbol: String, through: LocalDate, count: Int = 2): MutableMap<LocalDate, List<YahooBar>> = try {
        jdbc.query(
            "SELECT trade_date, candles FROM yahoo_minute_day WHERE symbol = ? AND trade_date <= ? ORDER BY trade_date DESC LIMIT ?",
            { rs, _ -> rs.getObject(1, LocalDate::class.java) to MinuteDayJson.yahooBars(rs.getString(2)) },
            symbol, through, count,
        ).toMap(LinkedHashMap())
    } catch (e: Exception) {
        log.warn("야후 분봉 DB 조회 실패 symbol={}", symbol, e)
        LinkedHashMap()
    }

    fun put(symbol: String, day: LocalDate, bars: List<YahooBar>) {
        if (bars.isEmpty()) return
        try {
            jdbc.update(
                "INSERT INTO yahoo_minute_day (symbol, trade_date, candles) VALUES (?, ?, ?) AS new ON DUPLICATE KEY UPDATE candles = new.candles",
                symbol, day, MinuteDayJson.yahooBars(bars),
            )
        } catch (e: Exception) {
            log.warn("야후 분봉 DB 저장 실패 symbol={} day={}", symbol, day, e)
        }
    }
}
