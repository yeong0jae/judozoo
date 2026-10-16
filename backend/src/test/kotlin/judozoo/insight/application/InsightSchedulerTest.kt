package judozoo.insight.application

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Clock
import java.time.ZonedDateTime

/** 왜 오르나 스케줄 — 휴장일은 쉬고, 해외는 뉴욕 시간대로 깨어난다. */
class InsightSchedulerTest {
    private val service = mockk<InsightService>()
    private val calendar = mockk<MarketCalendar>()
    private val registry = SimpleMeterRegistry()
    private val scheduler = InsightScheduler(service, calendar, JobFailures(registry), Clock.system(KST))

    @Nested
    inner class 깨어나는_때 {
        @Test
        fun `휴장일에는 만들지 않는다`() {
            every { calendar.isHoliday(any()) } returns true

            scheduler.runDomestic()

            verify(exactly = 0) { service.run(any(), any()) }
        }

        @Test
        fun `실패해도 삼키고 실패로 센다`() {
            every { calendar.isHoliday(any()) } returns false
            every { service.run(any(), any()) } throws IllegalStateException("풀 조회 실패")

            scheduler.runOverseas()

            registry.find("scheduler.job.errors").tag("job_id", "insight-reason-overseas").counter()!!.count() shouldBe 1.0
        }

        /** 뉴욕 08:00 — 서머타임엔 KST 21시, 표준시엔 22시. */
        @Test
        fun `해외 첫 실행은 한국 시각으로 여름 21시 겨울 22시다`() {
            val scheduled = InsightScheduler::class.java.getMethod("runOverseas").getAnnotation(Scheduled::class.java)
            val cron = CronExpression.parse(scheduled.cron)
            val zone = java.time.ZoneId.of(scheduled.zone)

            listOf(9 to 21, 12 to 22).forEach { (month, hour) ->
                val before = ZonedDateTime.of(2026, month, 28, hour - 1, 59, 0, 0, KST).withZoneSameInstant(zone)
                cron.next(before)!!.withZoneSameInstant(KST).hour shouldBe hour
            }
        }
    }
}
