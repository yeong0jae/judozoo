package judozoo.library

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 폴러가 삼킨 실패를 센다 — Prometheus에는 `scheduler_job_errors_total{job_id}`로 나간다. Python `metrics.job_failed`와 같은 이름이다.
 *
 * 폴러는 예외를 경고 로그로 흘리고 정상 리턴한다. 세지 않으면 화면은 멀쩡한 채 로그에만 남는다.
 * 알림 규칙(`scheduler-swallowed-errors`)이 서비스를 가리지 않고 `job_id`별로 합쳐 본다 — **job_id는 Python과 같은 값을 쓴다.**
 */
@Component
class JobFailures(private val registry: MeterRegistry) {
    fun record(jobId: String) {
        registry.counter("scheduler.job.errors", "job_id", jobId).increment()
    }
}
