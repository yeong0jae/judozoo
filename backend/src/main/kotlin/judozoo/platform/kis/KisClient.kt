package judozoo.platform.kis

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.RateLimiter
import judozoo.library.brokertoken.TokenSource
import judozoo.library.web.MeteredInterceptor
import org.slf4j.LoggerFactory
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration

class KisClient(
    private val properties: KisProperties,
    private val token: TokenSource,
    registry: MeterRegistry,
    private val pause: (Duration) -> Unit = { Thread.sleep(it) },
    restClient: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    private val limiter = RateLimiter(
        "kis-query", permitsPerPeriod = 1, registry = registry,
        period = { Duration.ofNanos(1_000_000_000L / properties.queryPermitsPerSecond) },
        timeout = Duration.ofSeconds(30),
    )

    private val rest = restClient.clone()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .requestInterceptor(MeteredInterceptor("kis", registry))
        .requestInterceptor(ClientHttpRequestInterceptor { request, body, execution -> limiter.acquire(); execution.execute(request, body) })
        .build()

    fun get(path: String, trId: String, params: Map<String, String>): KisResponse {
        val first = getOnce(path, trId, params)
        if (!first.rateLimited) return first
        log.warn("KIS 한도 초과 — 1초 뒤 다시 ({} {})", trId, path)
        pause(Duration.ofSeconds(1))
        return getOnce(path, trId, params)
    }

    private fun getOnce(path: String, trId: String, params: Map<String, String>): KisResponse =
        rest.get()
            .uri { builder -> builder.path(path).apply { params.forEach { (k, v) -> queryParam(k, v) } }.build() }
            .header("content-type", "application/json; charset=utf-8")
            .header("authorization", "Bearer ${token.get()}")
            .header("appkey", properties.appKey)
            .header("appsecret", properties.appSecret)
            .header("tr_id", trId)
            .header("custtype", "P")
            .exchange { _, response ->
                val bytes = response.body.readAllBytes()
                KisResponse(response.statusCode.value(), runCatching { json.readTree(bytes) as JsonNode }.getOrNull()?.takeIf { it.isObject })
            }
}
