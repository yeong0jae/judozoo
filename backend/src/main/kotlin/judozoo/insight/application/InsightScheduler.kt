package judozoo.insight.application

import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
class InsightScheduler(
    private val service: InsightService,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 * 8-20 * * MON-FRI", zone = "Asia/Seoul")
    fun runDomestic() = run(Region.KR, DOMESTIC_JOB)

    @Scheduled(cron = "0 * 8-18 * * MON-FRI", zone = "America/New_York")
    fun runOverseas() = run(Region.US, OVERSEAS_JOB)

    private fun run(region: Region, job: String) {
        if (calendar.isHoliday(region)) return
        try {
            val made = service.run(region, LocalDateTime.now(clock.withZone(KST)))
            if (made > 0) log.info("왜 오르나 {} — {}종목", region, made)
        } catch (e: Exception) {
            log.warn("왜 오르나 실행 실패 {}", region, e)
            failures.record(job)
        }
    }

    companion object {
        const val DOMESTIC_JOB = "insight-reason-domestic"
        const val OVERSEAS_JOB = "insight-reason-overseas"
    }
}
