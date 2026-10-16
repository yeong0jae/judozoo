package judozoo.leadercalendar.application

import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class LeaderCalendarScheduler(
    private val service: LeaderCalendarService,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 1 20 * * MON-FRI", zone = "Asia/Seoul")
    fun snapshotDomestic() = snapshot(Region.KR, DOMESTIC_JOB, service::snapshotDomestic)

    @Scheduled(cron = "0 1 16 * * MON-FRI", zone = "America/New_York")
    fun snapshotOverseas() = snapshot(Region.US, OVERSEAS_JOB, service::snapshotOverseas)

    private fun snapshot(region: Region, job: String, take: (LocalDate, LocalDateTime) -> Int) {
        val tradeDate = region.today(clock.instant())
        try {
            val at = LocalDateTime.now(clock.withZone(KST))
            if (calendar.isHoliday(region)) service.recordClosed(region, tradeDate, at) else take(tradeDate, at)
        } catch (e: Exception) {
            log.warn("주도주 캘린더 스냅샷 실패 {} {}", region, tradeDate, e)
            failures.record(job)
        }
    }

    companion object {
        const val DOMESTIC_JOB = "leader-calendar-domestic"
        const val OVERSEAS_JOB = "leader-calendar-overseas"
    }
}
