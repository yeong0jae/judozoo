package judozoo.market.infrastructure

import judozoo.market.domain.FuturesSession
import judozoo.market.domain.MarketCandle
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class FuturesMinuteArchive(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(market: Market, contractCode: String, session: FuturesSession, day: LocalDate): List<MarketCandle>? = try {
        jdbc.query(
            "SELECT candles FROM futures_minute_day WHERE market = ? AND contract_code = ? AND night = ? AND trade_date = ?",
            { rs, _ -> rs.getString(1) }, market.name, contractCode, session == FuturesSession.NIGHT, day,
        ).firstOrNull()?.let(MinuteDayJson::byDateAndTime)
    } catch (e: Exception) {
        log.warn("선물 분봉 DB 조회 실패 market={} contract={} session={} day={}", market, contractCode, session, day, e)
        null
    }

    fun put(market: Market, contractCode: String, session: FuturesSession, day: LocalDate, bars: List<MarketCandle>) {
        if (bars.isEmpty()) return
        try {
            jdbc.update(
                "INSERT INTO futures_minute_day (market, contract_code, night, trade_date, candles) VALUES (?, ?, ?, ?, ?) " +
                    "AS new ON DUPLICATE KEY UPDATE candles = new.candles",
                market.name, contractCode, session == FuturesSession.NIGHT, day, MinuteDayJson.byDateAndTime(bars),
            )
        } catch (e: Exception) {
            log.warn("선물 분봉 DB 저장 실패 market={} contract={} session={} day={}", market, contractCode, session, day, e)
        }
    }
}
