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

/**
 * 키움 공통 HTTP — 조회 공유 리미터, 토큰, `return_code` 검사, 토큰 무효(8005) 재시도.
 *
 * 엔드포인트 묶음(`KiwoomMarket` 등)은 [query]만 부르고 응답 JSON을 해석한다.
 */
class KiwoomClient(
    private val properties: KiwoomProperties,
    private val token: TokenSource,
    private val clock: Clock,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 조회 "초당 N건(09~10시 M건)"을 모든 조회 호출에 한 버킷으로 적용한다.
     *
     * 1초에 N개를 한꺼번에 충전하지 않고 (1/N)초마다 1개씩 균등 발급한다 — 폴러·돌파·스파이크가
     * 같은 종목 분봉을 몰아 부를 때의 버스트를 여기서 평탄화한다.
     */
    private val limiter = RateLimiter(
        "kiwoom-query", permitsPerPeriod = 1, registry = registry,
        period = { queryPeriod(LocalTime.now(clock.withZone(KST)), properties) },
        timeout = Duration.ofSeconds(20),
    )

    private val rest = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                // Python httpx와 같은 HTTP/1.1. 기본(HTTP/2)은 평문에서 h2c 업그레이드를 시도한다
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        // 경로 하나에 TR이 여럿 실린다 — 기능을 가르는 건 api-id다
        .requestInterceptor(MeteredInterceptor("kiwoom", registry) { it.headers.getFirst(API_ID) ?: it.uri.path })
        .requestInterceptor(queryRateLimit(limiter))
        .build()

    /**
     * 조회 TR 한 쪽을 부른다. `return_code` ≠ 0이면 [KiwoomApiException].
     *
     * 토큰 무효면 토큰을 버리고 **한 번만** 다시 부른다. 공존하는 동안 새 토큰은 Python이 남긴다 —
     * 아직 없으면 두 번째 시도는 [judozoo.library.BrokerTokenUnavailable]로 끝난다.
     */
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

        /** 조회 TR. 주문/계좌(kt*)는 별도 한도라 리미터를 태우지 않는다. */
        private const val QUERY_PREFIX = "ka"

        /** 키움 조회 한도가 내려가는 시간대(KST). 장 시작 직후 한 시간 — 분봉 캐시를 처음 채우는 때와 겹친다. */
        private val PEAK_START = LocalTime.of(9, 0)
        private val PEAK_END = LocalTime.of(10, 0)

        /** 지금 허가 하나를 내주는 간격. 피크타임엔 한도가 낮아 간격이 길다. */
        fun queryPeriod(at: LocalTime, properties: KiwoomProperties): Duration {
            val peak = !at.isBefore(PEAK_START) && at.isBefore(PEAK_END)
            val perSecond = if (peak) properties.peakQueryPermitsPerSecond else properties.queryPermitsPerSecond
            return Duration.ofNanos(1_000_000_000L / perSecond)
        }

        /** 조회 TR에만 허가를 요구한다. */
        fun queryRateLimit(limiter: RateLimiter) = ClientHttpRequestInterceptor { request, body, execution ->
            if (request.headers.getFirst(API_ID).orEmpty().startsWith(QUERY_PREFIX)) limiter.acquire()
            execution.execute(request, body)
        }
    }
}
