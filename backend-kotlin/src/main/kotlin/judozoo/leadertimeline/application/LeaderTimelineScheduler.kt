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

/**
 * 주도주 타임라인 스냅샷 — 매분 0초. 국내 08:01~20:00 KST, 해외 04:00~20:00 뉴욕(마감 분 포함).
 *
 * 해외를 KST로 걸지 않는다 — 서머타임이 끝나면(11월) 한 시간 어긋난다.
 * 쉬는 구간·휴장일은 찍지 않는다. 실패하면 그 분을 남기지 않는다 — 화면엔 띠가 끊긴 분으로 보인다.
 */
@Component
class LeaderTimelineScheduler(
    private val service: LeaderTimelineService,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 20시대는 20:00만 찍힌다 — 나머지 분은 [CaptureWindow]가 거른다. */
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
