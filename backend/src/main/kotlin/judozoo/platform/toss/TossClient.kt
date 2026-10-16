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

class TossClient(
    properties: TossProperties,
    private val token: TokenSource,
    registry: MeterRegistry,
    private val pause: (Duration) -> Unit = { Thread.sleep(it) },
    restClient: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val chartLimiter = RateLimiter(
        "toss-chart", 1, registry, { Duration.ofNanos(1_000_000_000L / properties.chartPermitsPerSecond) }, Duration.ofSeconds(20),
    )

    val indicatorChartLimiter = RateLimiter(
        "toss-indicator-chart", 1, registry,
        { Duration.ofNanos(1_000_000_000L / properties.indicatorChartPermitsPerSecond) }, Duration.ofSeconds(20),
    )

    private val rest = restClient.clone()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .requestInterceptor(MeteredInterceptor("toss", registry) { endpointLabel(it.uri.path) })
        .build()

    fun get(path: String, params: Map<String, Any>, limiter: RateLimiter? = null): JsonNode = try {
        withTokenRetry { getOnce(path, params, limiter) }
    } catch (e: HttpClientErrorException.TooManyRequests) {
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
        fun endpointLabel(path: String): String {
            val parts = path.split("/").toMutableList()
            val i = parts.indexOf("market-indicators")
            if (i >= 0 && i + 1 < parts.size) parts[i + 1] = "{symbol}"
            return parts.joinToString("/")
        }
    }
}
