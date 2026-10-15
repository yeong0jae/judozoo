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

/**
 * KIS 공통 HTTP — 공유 리미터, 토큰, 공통 헤더, 한도 초과(EGW00201) 1회 재시도.
 *
 * 한도 초과 재시도는 Python에서 해외 시총·분봉만 했다. 여기서는 모든 KIS 호출이 한다 — 다른 엔드포인트도
 * 같은 한도를 쓰고, 한 번 쉬었다 다시 부르는 것이 실패를 돌려주는 것보다 못한 경우가 없다.
 */
class KisClient(
    private val properties: KisProperties,
    private val token: TokenSource,
    registry: MeterRegistry,
    private val pause: (Duration) -> Unit = { Thread.sleep(it) },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    /**
     * 모든 KIS 호출에 한 버킷.
     *
     * **1초에 N개를 한꺼번에 충전하지 않는다** — 1초 경계에서 최대 2N이 몰려 EGW00201이 난다. (1/N)초마다 1개씩 낸다.
     * 페이징·동시요청이 큐잉돼도 거부 대신 30초까지 기다린다.
     */
    private val limiter = RateLimiter(
        "kis-query", permitsPerPeriod = 1, registry = registry,
        period = { Duration.ofNanos(1_000_000_000L / properties.queryPermitsPerSecond) },
        timeout = Duration.ofSeconds(30),
    )

    private val rest = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        // 계측을 리미터 **바깥**에 감는다. KIS는 경로가 엔드포인트마다 달라 경로를 라벨로 쓴다
        .requestInterceptor(MeteredInterceptor("kis", registry))
        .requestInterceptor(ClientHttpRequestInterceptor { request, body, execution -> limiter.acquire(); execution.execute(request, body) })
        .build()

    /** GET 한 번. 한도 초과면 1초 쉬고 한 번만 다시 부른다. 연결 실패·타임아웃은 예외로 올라간다. */
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
