package judozoo.platform.kiwoom

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.KST
import judozoo.library.RateLimiter
import judozoo.library.brokertoken.TokenSource
import judozoo.library.web.MeteredInterceptor
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.LocalTime

class KiwoomClient(
    private val properties: KiwoomProperties,
    private val token: TokenSource,
    private val clock: Clock,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val limiter = RateLimiter(
        "kiwoom-query", permitsPerPeriod = 1, registry = registry,
        period = { queryPeriod(LocalTime.now(clock.withZone(KST)), properties) },
        timeout = Duration.ofSeconds(20),
    )

    private val rest = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .requestInterceptor(MeteredInterceptor("kiwoom", registry) { it.headers.getFirst(API_ID) ?: it.uri.path })
        .requestInterceptor(queryRateLimit(limiter))
        .build()

    fun query(path: String, apiId: String, body: Map<String, Any?>, contYn: String = "N", nextKey: String = ""): KiwoomResponse =
        try {
            queryOnce(path, apiId, body, contYn, nextKey)
        } catch (e: KiwoomApiException) {
            if (!e.isInvalidToken) throw e
            log.warn("Kiwoom 토큰 무효(8005) 감지 — 캐시 무효화 후 1회 재시도")
            token.invalidate()
            queryOnce(path, apiId, body, contYn, nextKey)
        }

    private fun queryOnce(path: String, apiId: String, body: Map<String, Any?>, contYn: String, nextKey: String): KiwoomResponse {
        val entity = rest.post()
            .uri(path)
            .contentType(MediaType("application", "json", Charsets.UTF_8))
            .header("authorization", "Bearer ${token.get()}")
            .header(API_ID, apiId)
            .header("cont-yn", contYn)
            .header("next-key", nextKey)
            .body(body)
            .retrieve()
            .toEntity(JsonNode::class.java)
        val json = entity.body ?: throw KiwoomApiException(apiId, -1, "빈 응답")

        val code = json.get("return_code")?.asInt() ?: 0
        if (code != 0) throw KiwoomApiException(apiId, code, json.get("return_msg")?.asString())

        return KiwoomResponse(json, entity.headers.getFirst("cont-yn"), entity.headers.getFirst("next-key"))
    }

    companion object {
        const val API_ID = "api-id"

        private const val QUERY_PREFIX = "ka"

        private val PEAK_START = LocalTime.of(9, 0)
        private val PEAK_END = LocalTime.of(10, 0)

        fun queryPeriod(at: LocalTime, properties: KiwoomProperties): Duration {
            val peak = !at.isBefore(PEAK_START) && at.isBefore(PEAK_END)
            val perSecond = if (peak) properties.peakQueryPermitsPerSecond else properties.queryPermitsPerSecond
            return Duration.ofNanos(1_000_000_000L / perSecond)
        }

        fun queryRateLimit(limiter: RateLimiter) = ClientHttpRequestInterceptor { request, body, execution ->
            if (request.headers.getFirst(API_ID).orEmpty().startsWith(QUERY_PREFIX)) limiter.acquire()
            execution.execute(request, body)
        }
    }
}
