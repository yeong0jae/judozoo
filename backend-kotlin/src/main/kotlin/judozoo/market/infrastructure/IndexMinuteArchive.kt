package judozoo.market.infrastructure

import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 지수의 완성된 지난 거래일 분봉(`index_minute_day`). 부분 수집·당일 봉은 넣지 않는다.
 * DB 장애 시 조회는 null(외부 조회로 폴백), 저장은 경고만 남긴다.
 *
 * 같은 날은 같은 내용으로 덮는 upsert라 공존하는 동안 Python이 같은 날을 써도 결과가 같다.
 */
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
