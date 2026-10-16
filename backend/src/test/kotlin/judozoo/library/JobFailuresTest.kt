package judozoo.library

import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test

class JobFailuresTest {
    @Test
    fun `Python과 같은 이름으로 잡별 실패를 센다`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val failures = JobFailures(registry)

        failures.record("signal-event-poller")
        failures.record("signal-event-poller")

        registry.scrape() shouldContain """scheduler_job_errors_total{job_id="signal-event-poller"} 2.0"""
    }
}
