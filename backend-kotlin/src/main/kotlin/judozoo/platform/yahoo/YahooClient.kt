package judozoo.platform.yahoo

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.KST
import judozoo.library.web.MeteredInterceptor
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 야후 파이낸스 chart API — 시세 요약(meta) + 캔들(timestamp/quote)을 한 응답으로 준다.
 *
 * 인증 없는 공개 엔드포인트다. KIS는 CME 시세가 유료라 나스닥 선물을 못 받아, 그 대체 소스로 쓴다.
 * 나스닥 선물은 심볼 `NQ=F`만 넘기면 야후가 근월물을 알아서 물려준다.
 *
 * 시각은 epoch 초로 오므로 KST로 변환해 넘긴다. 실패 시 null / 빈 목록.
 */
@Component
class YahooClient(properties: YahooProperties, registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class YahooQuote(val name: String, val price: Double, val prevClose: Double)

    data class YahooBar(
        /** yyyy-MM-dd (KST) */
        val date: String,
        /** HH:mm:ss (KST) */
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    private val rest = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        // User-Agent가 없으면 야후가 차단한다
        .defaultHeader("User-Agent", "Mozilla/5.0")
        // 심볼이 경로에 박혀 있다. 그대로 두면 조회 심볼 수만큼 시계열이 생긴다
        .requestInterceptor(MeteredInterceptor("yahoo", registry) { if ("/chart/" in it.uri.path) "/v8/finance/chart/{symbol}" else it.uri.path })
        .build()

    /**
     * 시세 요약 — 현재가·전일종가(직전 세션 마감가). 등락은 호출측이 계산한다.
     *
     * `chartPreviousClose`는 '조회 창 직전의 종가'라 range에 따라 값이 달라진다. 그래서 `previousClose`를 우선한다.
     */
    fun fetchQuote(symbol: String): YahooQuote? {
        val meta = fetch(symbol, "1m", "1d")?.get("meta") ?: return null
        val price = meta.number("regularMarketPrice") ?: return null
        val prevClose = meta.number("previousClose") ?: meta.number("chartPreviousClose") ?: return null
        return YahooQuote(meta.text("shortName"), price, prevClose)
    }

    /**
     * 시세 — 프리·애프터마켓 체결까지 반영한다(관심 종목 시세용).
     *
     * meta는 정규장 값만 담는다. 프리마켓 중엔 `regularMarketPrice`가 '직전 정규장 종가'에 멈춰 있고
     * `previousClose`는 한 세션 더 과거를 가리킨다. 그래서 확장시간엔 마지막 캔들을 현재가로,
     * 직전 정규장 종가를 기준가로 쓴다. 정규장 중엔 meta를 그대로 쓴다.
     */
    fun fetchExtendedQuote(symbol: String): YahooQuote? {
        val result = fetch(symbol, "1m", "1d", includePrePost = true) ?: return null
        val meta = result["meta"] ?: return null
        val regularPrice = meta.number("regularMarketPrice") ?: return null

        val lastBar = lastClose(result)
        val regularTime = meta.get("regularMarketTime")?.takeUnless { it.isNull }?.asLong()
        if (lastBar != null && regularTime != null && lastBar.first > regularTime) {
            return YahooQuote(meta.text("shortName"), lastBar.second, regularPrice)
        }
        val prevClose = meta.number("previousClose") ?: meta.number("chartPreviousClose") ?: return null
        return YahooQuote(meta.text("shortName"), regularPrice, prevClose)
    }

    /**
     * 캔들 — [interval]("1m"/"1d"), [range]("1d","5d","6mo" 등). 시각 오름차순.
     *
     * 거래가 없던 분은 값이 null로 오므로 버린다. 세션 마감 봉은 거래량 0짜리 중복으로 한 번 더 오므로
     * **같은 시각은 뒤엣것으로 덮는다**(차트는 시각이 유일해야 한다).
     */
    fun fetchCandles(symbol: String, interval: String, range: String): List<YahooBar> {
        val result = fetch(symbol, interval, range) ?: return emptyList()
        val times = result["timestamp"]?.values()?.toList().orEmpty()
        val quote = firstQuote(result) ?: return emptyList()

        val byTime = sortedMapOf<String, YahooBar>() // "yyyy-MM-dd HH:mm:ss"라 사전순이 곧 시각순이다
        times.forEachIndexed { i, epoch ->
            val close = quote.at("close", i) ?: return@forEachIndexed
            val at = Instant.ofEpochSecond(epoch.asLong()).atZone(KST)
            val bar = YahooBar(
                date = at.format(DATE), time = at.format(TIME),
                open = quote.at("open", i) ?: close, high = quote.at("high", i) ?: close, low = quote.at("low", i) ?: close,
                close = close, volume = quote.at("volume", i) ?: 0.0,
            )
            byTime["${bar.date} ${bar.time}"] = bar
        }
        return byTime.values.toList()
    }

    /** 마지막으로 체결된 캔들(거래 없는 분은 close가 null로 온다) — (epoch, 종가). */
    private fun lastClose(result: JsonNode): Pair<Long, Double>? {
        val times = result["timestamp"]?.values()?.toList().orEmpty()
        val quote = firstQuote(result) ?: return null
        return times.indices.reversed().firstNotNullOfOrNull { i -> quote.at("close", i)?.let { times[i].asLong() to it } }
    }

    private fun firstQuote(result: JsonNode): JsonNode? = result["indicators"]?.get("quote")?.values()?.firstOrNull()

    private fun fetch(symbol: String, interval: String, range: String, includePrePost: Boolean = false): JsonNode? = try {
        val chart = rest.get()
            .uri { b ->
                b.path("/v8/finance/chart/{symbol}").queryParam("interval", interval).queryParam("range", range)
                    .apply { if (includePrePost) queryParam("includePrePost", "true") }
                    .build(symbol)
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?.get("chart")
        val error = chart?.get("error")
        if (error != null && !error.isNull) {
            log.error("야후 차트 오류: {} (symbol={})", error, symbol)
            null
        } else {
            chart?.get("result")?.values()?.firstOrNull()
        }
    } catch (e: Exception) {
        log.error("야후 차트 조회 실패 (symbol={}, interval={})", symbol, interval, e)
        null
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        private fun JsonNode.number(field: String): Double? = get(field)?.takeIf { it.isNumber }?.asDouble()
        private fun JsonNode.text(field: String): String = get(field)?.takeUnless { it.isNull }?.asString().orEmpty()

        /** 배열 [field]의 i번째 숫자. 없거나 null이면 null. */
        private fun JsonNode.at(field: String, index: Int): Double? =
            get(field)?.takeIf { it.isArray }?.get(index)?.takeIf { it.isNumber }?.asDouble()
    }
}
