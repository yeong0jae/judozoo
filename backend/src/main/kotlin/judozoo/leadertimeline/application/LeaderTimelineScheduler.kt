package judozoo.leadertimeline.application

import judozoo.leadertimeline.domain.CaptureWindow
import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Component
class LeaderTimelineScheduler(
    private val service: LeaderTimelineService,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 * 8-20 * * MON-FRI", zone = "Asia/Seoul")
    fun snapshotDomestic() = snapshot(Region.KR, DOMESTIC_JOB)

    @Scheduled(cron = "0 * 4-20 * * MON-FRI", zone = "America/New_York")
    fun snapshotOverseas() = snapshot(Region.US, OVERSEAS_JOB)

    private fun snapshot(region: Region, job: String) {
        val takenAt = LocalDateTime.now(clock.withZone(KST))
        val at = LocalDateTime.now(clock.withZone(region.zone)).truncatedTo(ChronoUnit.MINUTES)
        if (!CaptureWindow.of(region).captures(at.toLocalTime()) || calendar.isHoliday(region)) return
        try {
            service.snapshot(region, at, takenAt)
        } catch (e: Exception) {
            log.warn("주도주 타임라인 스냅샷 실패 {} {}", region, at, e)
            failures.record(job)
        }
    }

    companion object {
        const val DOMESTIC_JOB = "leader-timeline-domestic"
        const val OVERSEAS_JOB = "leader-timeline-overseas"
    }
}
