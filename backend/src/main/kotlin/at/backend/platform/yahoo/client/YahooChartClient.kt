package at.backend.platform.yahoo.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.ZoneId

/**
 * 야후 파이낸스 chart API — 시세 요약(meta) + 캔들(timestamp/quote)을 한 응답으로 준다.
 * 나스닥 선물은 심볼 "NQ=F"만 넘기면 야후가 근월물을 알아서 물려준다(코드 조립 불필요).
 * 시각은 epoch 초로 오므로 KST로 변환해 넘긴다. 실패 시 null/빈리스트.
 */
@Component
class YahooChartClient(
    private val yahooRestClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [symbol] 시세 요약 — 현재가·전일종가(직전 세션 마감가). 등락은 호출측이 계산한다.
     * chartPreviousClose는 '조회 창 직전의 종가'라 range에 따라 값이 달라진다. previousClose를 우선한다.
     */
    fun fetchQuote(symbol: String): YahooQuote? {
        val meta = fetch(symbol, "1m", "1d")?.meta ?: return null
        val price = meta.regularMarketPrice ?: return null
        val prevClose = meta.previousClose ?: meta.chartPreviousClose ?: return null
        return YahooQuote(
            name = meta.shortName.orEmpty(),
            price = price,
            prevClose = prevClose,
        )
    }

    /**
     * [symbol] 캔들 — [interval]("1m"/"1d"), [range]("1d","5d","6mo" 등).
     * 거래가 없던 분은 값이 null로 오므로 버린다.
     * 세션 마감 봉은 거래량 0짜리 중복으로 한 번 더 오므로 같은 시각은 뒤엣것으로 덮는다(차트는 시각이 유일해야 한다).
     * 시각 오름차순.
     */
    fun fetchCandles(symbol: String, interval: String, range: String): List<YahooBar> {
        val result = fetch(symbol, interval, range) ?: return emptyList()
        val times = result.timestamp ?: return emptyList()
        val q = result.indicators?.quote?.firstOrNull() ?: return emptyList()
        val byTime = sortedMapOf<String, YahooBar>()
        times.indices.forEach { i ->
            val close = q.close?.getOrNull(i) ?: return@forEach
            val at = Instant.ofEpochSecond(times[i]).atZone(KST)
            val bar = YahooBar(
                date = at.toLocalDate().toString(),
                time = at.toLocalTime().withNano(0).toString().let { if (it.length == 5) "$it:00" else it },
                open = q.open?.getOrNull(i) ?: close,
                high = q.high?.getOrNull(i) ?: close,
                low = q.low?.getOrNull(i) ?: close,
                close = close,
                volume = (q.volume?.getOrNull(i) ?: 0L).toDouble(),
            )
            byTime["${bar.date} ${bar.time}"] = bar
        }
        return byTime.values.toList()
    }

    private fun fetch(symbol: String, interval: String, range: String): ChartResult? {
        try {
            val response = yahooRestClient.get()
                .uri { b ->
                    b.path("/v8/finance/chart/{symbol}")
                        .queryParam("interval", interval)
                        .queryParam("range", range)
                        .build(symbol)
                }
                .retrieve()
                .body(ChartResponse::class.java)
                ?: return null
            response.chart?.error?.let {
                log.error("야후 차트 오류: {} (symbol={})", it, symbol)
                return null
            }
            return response.chart?.result?.firstOrNull()
        } catch (e: Exception) {
            log.error("야후 차트 조회 실패 (symbol={}, interval={})", symbol, interval, e)
            return null
        }
    }

    // ── 응답 DTO (필요 필드만) ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChartResponse(val chart: Chart? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Chart(val result: List<ChartResult>? = null, val error: Any? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChartResult(
        val meta: Meta? = null,
        val timestamp: List<Long>? = null,
        val indicators: Indicators? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Meta(
        val shortName: String? = null,
        val regularMarketPrice: Double? = null,
        val previousClose: Double? = null,
        val chartPreviousClose: Double? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Indicators(val quote: List<Quote>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Quote(
        val open: List<Double?>? = null,
        val high: List<Double?>? = null,
        val low: List<Double?>? = null,
        val close: List<Double?>? = null,
        val volume: List<Long?>? = null,
    )

    // ── 결과 타입 ──
    data class YahooQuote(val name: String, val price: Double, val prevClose: Double)

    data class YahooBar(
        val date: String, // yyyy-MM-dd (KST)
        val time: String, // HH:mm:ss (KST)
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
