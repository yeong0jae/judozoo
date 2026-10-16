package judozoo.platform.toss

import judozoo.leadingstock.domain.MinuteCandle
import judozoo.library.KST
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

@Component
class TossCandles(private val client: TossClient, private val clock: Clock) {
    fun fetchTodayMinuteCandles(stockCode: String, since: LocalDateTime? = null): List<MinuteCandle> {
        val now = LocalDateTime.now(clock.withZone(KST))
        val day = now.toLocalDate()
        val floor = since ?: day.atStartOfDay()
        val symbol = stockCode.substringBefore("_")
        var count = if (since == null) PAGE_SIZE else minOf(PAGE_SIZE, (Duration.between(since, now).seconds / 60).toInt() + 3)

        val candles = sortedMapOf<LocalDateTime, MinuteCandle>()
        var before: String? = null
        repeat(MAX_PAGES) {
            val result = page(symbol, maxOf(count, 1), before)
            val page = result["candles"]?.values().orEmpty().map(::toCandle)
            page.filter { it.dateTime.toLocalDate() == day && !it.dateTime.isBefore(floor) && it.volume > 0 }
                .forEach { candles[it.dateTime] = it }
            before = result["nextBefore"]?.takeUnless { it.isNull }?.asString()
            val oldest = page.minOfOrNull { it.dateTime }
            if (before.isNullOrEmpty() || oldest == null || !oldest.isAfter(floor)) return candles.values.toList()
            count = PAGE_SIZE
        }
        return candles.values.toList()
    }

    private fun page(symbol: String, count: Int, before: String?): JsonNode {
        val params = buildMap<String, Any> {
            put("symbol", symbol)
            put("interval", "1m")
            put("count", count)
            put("adjusted", "true")
            before?.let { put("before", it) }
        }
        return client.get("/api/v1/candles", params, client.chartLimiter)["result"] ?: JSON_EMPTY
    }

    private fun toCandle(raw: JsonNode): MinuteCandle {
        val at = OffsetDateTime.parse(raw["timestamp"].asString()).atZoneSameInstant(KST).toLocalDateTime().minusMinutes(1)
        fun number(field: String) = BigDecimal(raw[field].asString()).toLong()
        val close = number("closePrice")
        val volume = number("volume")
        return MinuteCandle(
            dateTime = at,
            openPrice = number("openPrice"),
            highPrice = number("highPrice"),
            lowPrice = number("lowPrice"),
            closePrice = close,
            volume = volume,
            tradingValue = close * volume,
        )
    }

    companion object {
        private const val PAGE_SIZE = 200

        private const val MAX_PAGES = 5

        private val JSON_EMPTY: JsonNode = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
    }
}
