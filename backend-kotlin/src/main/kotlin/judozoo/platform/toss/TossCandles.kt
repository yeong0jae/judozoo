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

/**
 * 토스 캔들 — 국내 종목 당일 1분봉 (`/api/v1/candles`, MARKET_DATA_CHART 그룹).
 *
 * 키움 ka10080과 같은 봉이다(NXT 프리·애프터 포함). 다른 점 두 가지를 여기서 키움 모양으로 맞춘다.
 * 1. 시각이 봉의 **끝**이다(`[timestamp-1분, timestamp)`). 1분 당겨 키움 `cntr_tm`(봉 시작)과 맞춘다.
 * 2. 체결 없는 분도 거래량 0 봉으로 채운다. **버린다** — 두면 스파이크의 직전 평균과 이평이 달라진다.
 *
 * 한 번에 200봉까지라 하루치(최대 720봉)는 최신부터 거꾸로 여러 번 받는다.
 */
@Component
class TossCandles(private val client: TossClient, private val clock: Clock) {

    /**
     * 오늘 1분봉 중 [since] 이후(포함) 봉만, 시각 오름차순. [since]가 없으면 오늘치 전부.
     *
     * 실패는 예외로 올린다 — 이어 붙이는 쪽이 기존 봉을 지킬지 스스로 정한다.
     */
    fun fetchTodayMinuteCandles(stockCode: String, since: LocalDateTime? = null): List<MinuteCandle> {
        val now = LocalDateTime.now(clock.withZone(KST))
        val day = now.toLocalDate()
        val floor = since ?: day.atStartOfDay()
        val symbol = stockCode.substringBefore("_") // 랭킹 코드의 `_AL` — 토스 심볼에 `_`는 못 쓴다
        // 받을 만큼만 청한다. 증분이면 대개 몇 봉이라 한 번에 끝난다
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
            // 과거 분봉(키움)이 수정주가라 오늘 봉도 같은 기준이어야 돌파선이 맞는다
            put("adjusted", "true")
            before?.let { put("before", it) }
        }
        return client.get("/api/v1/candles", params, client.chartLimiter)["result"] ?: JSON_EMPTY
    }

    private fun toCandle(raw: JsonNode): MinuteCandle {
        // 봉 끝 시각 → 봉 시작 시각(키움과 같은 기준)
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
            tradingValue = close * volume, // 키움과 같은 근사 — 응답에 거래대금이 없다
        )
    }

    companion object {
        private const val PAGE_SIZE = 200

        /** 하루치(720봉)를 넘겨 받지 않게 막는 상한 — 응답이 이상해도 끝없이 돌지 않는다. */
        private const val MAX_PAGES = 5

        private val JSON_EMPTY: JsonNode = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
    }
}
