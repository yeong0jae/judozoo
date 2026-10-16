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

@Component
class YahooClient(properties: YahooProperties, registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class YahooQuote(val name: String, val price: Double, val prevClose: Double)

    data class YahooBar(
        val date: String,
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
        .defaultHeader("User-Agent", "Mozilla/5.0")
        .requestInterceptor(MeteredInterceptor("yahoo", registry) { if ("/chart/" in it.uri.path) "/v8/finance/chart/{symbol}" else it.uri.path })
        .build()

    fun fetchQuote(symbol: String): YahooQuote? {
        val meta = fetch(symbol, "1m", "1d")?.get("meta") ?: return null
        val price = meta.number("regularMarketPrice") ?: return null
        val prevClose = meta.number("previousClose") ?: meta.number("chartPreviousClose") ?: return null
        return YahooQuote(meta.text("shortName"), price, prevClose)
    }

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

    fun fetchCandles(symbol: String, interval: String, range: String): List<YahooBar> {
        val result = fetch(symbol, interval, range) ?: return emptyList()
        val times = result["timestamp"]?.values()?.toList().orEmpty()
        val quote = firstQuote(result) ?: return emptyList()

        val byTime = sortedMapOf<String, YahooBar>()
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

        private fun JsonNode.at(field: String, index: Int): Double? =
            get(field)?.takeIf { it.isArray }?.get(index)?.takeIf { it.isNumber }?.asDouble()
    }
}
