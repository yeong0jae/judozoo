package judozoo.closingbet.application

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import judozoo.library.JobFailures
import judozoo.library.KST
import org.junit.jupiter.api.Test
import java.time.Clock

class ClosingBetSchedulerTest {
    @Test
    fun `체결·정산이 실패해도 작업이 죽지 않고 실패로 센다`() {
        val service = mockk<ClosingBetService> { every { tick(any()) } throws IllegalStateException("키움 오류") }
        val registry = SimpleMeterRegistry()

        ClosingBetScheduler(service, JobFailures(registry), Clock.system(KST)).tick()

        registry.find("scheduler.job.errors").tag("job_id", "closingbet-tick").counter()!!.count() shouldBe 1.0
    }
}
