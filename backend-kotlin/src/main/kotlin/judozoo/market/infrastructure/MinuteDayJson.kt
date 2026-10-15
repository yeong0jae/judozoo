package judozoo.market.infrastructure

import judozoo.platform.kis.KisFutures.FuturesBar
import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.platform.yahoo.YahooClient.YahooBar
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 분봉 보관 테이블(`*_minute_day.candles`)의 JSON 모양. **Python이 쓰던 키 그대로다** —
 * 공존하는 동안 두 쪽이 서로 쓴 행을 읽는다. 키를 바꾸면 한쪽이 상대 행을 못 읽는다.
 */
internal object MinuteDayJson {
    private val json = JsonMapper.builder().build()

    /** Python `datetime.isoformat()`과 같은 모양 — 초가 0이어도 적는다. */
    private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun tossCandles(candles: List<TossCandle>): String = json.writeValueAsString(
        candles.sortedBy { it.timestamp }.map {
            mapOf("timestamp" to it.timestamp.format(ISO_SECONDS), "open" to it.open, "high" to it.high, "low" to it.low, "close" to it.close, "volume" to it.volume)
        },
    )

    fun tossCandles(text: String): List<TossCandle> = json.readTree(text).values().map {
        TossCandle(LocalDateTime.parse(it["timestamp"].asString()), it.double("open"), it.double("high"), it.double("low"), it.double("close"), it.double("volume"))
    }

    fun futuresBars(bars: List<FuturesBar>): String = json.writeValueAsString(bars.sortedBy { it.date + it.time }.map(::barMap))

    fun futuresBars(text: String): List<FuturesBar> = json.readTree(text).values().map {
        FuturesBar(it["date"].asString(), it["time"].asString(), it.double("open"), it.double("high"), it.double("low"), it.double("close"), it.double("volume"))
    }

    fun yahooBars(bars: List<YahooBar>): String = json.writeValueAsString(
        bars.sortedBy { it.date + it.time }.map {
            mapOf("date" to it.date, "time" to it.time, "open" to it.open, "high" to it.high, "low" to it.low, "close" to it.close, "volume" to it.volume)
        },
    )

    fun yahooBars(text: String): List<YahooBar> = json.readTree(text).values().map {
        YahooBar(it["date"].asString(), it["time"].asString(), it.double("open"), it.double("high"), it.double("low"), it.double("close"), it.double("volume"))
    }

    private fun barMap(b: FuturesBar) =
        mapOf("date" to b.date, "time" to b.time, "open" to b.open, "high" to b.high, "low" to b.low, "close" to b.close, "volume" to b.volume)

    private fun JsonNode.double(field: String) = get(field).asDouble()
}
