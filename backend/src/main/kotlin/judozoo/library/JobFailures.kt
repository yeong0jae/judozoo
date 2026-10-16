package judozoo.library

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class JobFailures(private val registry: MeterRegistry) {
    fun record(jobId: String) {
        registry.counter("scheduler.job.errors", "job_id", jobId).increment()
    }
}
