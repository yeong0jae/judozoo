package judozoo.platform.toss

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.RateLimiter
import judozoo.library.brokertoken.TokenSource
import judozoo.library.web.MeteredInterceptor
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Duration

/**
 * 토스 공통 HTTP — 토큰, 401·429 재시도, 그룹별 한도.
 *
 * - **client당 유효 토큰은 1개**라 같은 client_id로 다른 프로세스가 발급받으면 이쪽 토큰이 즉시 죽는다.
 *   401을 보면 토큰을 버리고 한 번 다시 친다(공존하는 동안 새 토큰은 Python이 남긴다)
 * - 한도 초과(429)면 1초 쉬고 한 번 더. Python은 종목 캔들만 그랬다 — 여기서는 모든 토스 호출이 한다(KIS와 같은 판단)
 * - 한도는 그룹마다 따로다. 호출하는 쪽이 [chartLimiter]·[indicatorChartLimiter] 중 하나를 넘긴다. **시도마다** 허가를 받는다
 */
class TossClient(
    properties: TossProperties,
    private val token: TokenSource,
    registry: MeterRegistry,
    private val pause: (Duration) -> Unit = { Thread.sleep(it) },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 종목 캔들(MARKET_DATA_CHART 그룹). 휴장 조회(MARKET_DATA)와는 따로 센다. */
    val chartLimiter = RateLimiter(
        "toss-chart", 1, registry, { Duration.ofNanos(1_000_000_000L / properties.chartPermitsPerSecond) }, Duration.ofSeconds(20),
    )

    /** 지수 캔들(MARKET_INDICATOR_CHART 그룹). 1분봉 차트 한 번이 200봉 페이지를 여러 번 연달아 받는다. */
    val indicatorChartLimiter = RateLimiter(
        "toss-indicator-chart", 1, registry,
        { Duration.ofNanos(1_000_000_000L / properties.indicatorChartPermitsPerSecond) }, Duration.ofSeconds(20),
    )

    private val rest = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .requestInterceptor(MeteredInterceptor("toss", registry) { endpointLabel(it.uri.path) })
        .build()

    /** GET 한 번. 2xx가 아니면 [HttpClientErrorException] 등으로 올린다(401·429는 한 번씩 다시 부른 뒤). */
    fun get(path: String, params: Map<String, Any>, limiter: RateLimiter? = null): JsonNode = try {
        withTokenRetry { getOnce(path, params, limiter) }
    } catch (e: HttpClientErrorException.TooManyRequests) {
        // 한 번만 다시 부른다. 다시 부르는 것도 같은 리미터 앞에 줄을 서므로 한꺼번에 몰려 나가지 않는다
        log.warn("토스 한도 초과 — 1초 뒤 다시 ({})", path)
        pause(Duration.ofSeconds(1))
        withTokenRetry { getOnce(path, params, limiter) }
    }

    private fun <T> withTokenRetry(call: () -> T): T = try {
        call()
    } catch (e: HttpClientErrorException) {
        if (e.statusCode != HttpStatus.UNAUTHORIZED) throw e
        log.warn("Toss 토큰 무효(401) 감지 — 캐시 무효화 후 1회 재시도")
        token.invalidate()
        call()
    }

    private fun getOnce(path: String, params: Map<String, Any>, limiter: RateLimiter?): JsonNode {
        limiter?.acquire()
        return rest.get()
            .uri { builder -> builder.path(path).apply { params.forEach { (k, v) -> queryParam(k, v) } }.build() }
            .header("Authorization", "Bearer ${token.get()}")
            .retrieve()
            .body(JsonNode::class.java)!!
    }

    companion object {
        /** 메트릭 라벨 — `/api/v1/market-indicators/{symbol}/candles`의 심볼을 지운다. 종목 수만큼 시계열이 생기지 않게. */
        fun endpointLabel(path: String): String {
            val parts = path.split("/").toMutableList()
            val i = parts.indexOf("market-indicators")
            if (i >= 0 && i + 1 < parts.size) parts[i + 1] = "{symbol}"
            return parts.joinToString("/")
        }
    }
}
