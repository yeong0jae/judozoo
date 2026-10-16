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

class MeteredInterceptor(
    private val vendor: String,
    private val registry: MeterRegistry,
    private val endpointOf: (HttpRequest) -> String = { it.uri.path },
) : ClientHttpRequestInterceptor {
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
