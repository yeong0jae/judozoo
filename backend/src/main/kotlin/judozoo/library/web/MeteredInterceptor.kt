package judozoo.library.web

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 외부 호출을 계측한다. Python `MeteredTransport`와 같은 메트릭 이름이다.
 *
 * **리미터 바깥에 감는다**(인터셉터 중 먼저 등록). 화면이 느린 이유는 대개 리미터 대기라,
 * 호출자가 실제로 겪은 시간을 잰다. 대기만 따로 보려면 `rate_limiter_wait`를 본다.
 *
 * 타임아웃·연결 실패도 센다 — 예외 타입 이름이 status 라벨이 된다(유한한 집합이라 안전하다).
 */
class MeteredInterceptor(
    private val vendor: String,
    private val registry: MeterRegistry,
    private val endpointOf: (HttpRequest) -> String = { it.uri.path },
) : ClientHttpRequestInterceptor {
    // endpoint를 라벨에서 뺀다 — "지금 몇 개가 매달려 있나"는 합계로 읽는다
    private val inFlight = registry.gauge(
        "http_client_in_flight_requests", listOf(Tag.of("vendor", vendor)), AtomicInteger(0),
    )!!

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        val endpoint = endpointOf(request)
        val started = System.nanoTime()
        inFlight.incrementAndGet()
        try {
            val response = execution.execute(request, body)
            count(endpoint, response.statusCode.value().toString())
            return response
        } catch (e: Exception) {
            count(endpoint, e.javaClass.simpleName)
            throw e
        } finally {
            inFlight.decrementAndGet()
            duration(endpoint).record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun count(endpoint: String, status: String) =
        Counter.builder("http_client_requests")
            .description("외부 HTTP 호출 결과")
            .tags("vendor", vendor, "endpoint", endpoint, "status", status)
            .register(registry)
            .increment()

    // 리미터 대기를 포함하므로 20~30초가 정상 범위에 있다. 45·60은 Vertex 타임아웃(60초) 아래를 본다
    private fun duration(endpoint: String) =
        Timer.builder("http_client_request_duration")
            .description("외부 HTTP 응답 시간 — 레이트 리미터 대기 포함")
            .tags("vendor", vendor, "endpoint", endpoint)
            .serviceLevelObjectives(*BUCKETS)
            .register(registry)

    companion object {
        private val BUCKETS = listOf(100L, 250, 500, 1000, 2000, 5000, 10000, 20000, 30000, 45000, 60000)
            .map(Duration::ofMillis).toTypedArray()
    }
}
