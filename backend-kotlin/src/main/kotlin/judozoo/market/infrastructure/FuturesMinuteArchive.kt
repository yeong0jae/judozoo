package judozoo.market.infrastructure

import judozoo.platform.kis.KisFutures.FuturesBar
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** 완성된 지난 정규장 거래일·야간 세션 분봉을 계약별로 보관한다(`futures_minute_day`). */
@Repository
class FuturesMinuteArchive(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(market: Market, contractCode: String, night: Boolean, day: LocalDate): List<FuturesBar>? = try {
        jdbc.query(
            "SELECT candles FROM futures_minute_day WHERE market = ? AND contract_code = ? AND night = ? AND trade_date = ?",
            { rs, _ -> rs.getString(1) }, market.name, contractCode, night, day,
        ).firstOrNull()?.let(MinuteDayJson::futuresBars)
    } catch (e: Exception) {
        log.warn("선물 분봉 DB 조회 실패 market={} contract={} night={} day={}", market, contractCode, night, day, e)
        null
    }

    fun put(market: Market, contractCode: String, night: Boolean, day: LocalDate, bars: List<FuturesBar>) {
        if (bars.isEmpty()) return
        try {
            jdbc.update(
                "INSERT INTO futures_minute_day (market, contract_code, night, trade_date, candles) VALUES (?, ?, ?, ?, ?) " +
                    "AS new ON DUPLICATE KEY UPDATE candles = new.candles",
                market.name, contractCode, night, day, MinuteDayJson.futuresBars(bars),
            )
        } catch (e: Exception) {
            log.warn("선물 분봉 DB 저장 실패 market={} contract={} night={} day={}", market, contractCode, night, day, e)
        }
    }
}
