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

/**
 * 주도주 캘린더 스냅샷 — 국내 20:01 KST, 해외 16:01 뉴욕.
 *
 * 국내는 **홈 풀이 멈춘 뒤**다. 풀은 세션이 끝나면 다음 세션까지 마지막 값을 들고 있으므로 이 시각에 읽은 값이 그날 밤 홈에 뜬 값과 같다.
 *
 * 해외는 풀이 애프터마켓(~20:00)까지 돌지만 캘린더는 **정규장 마감**을 남긴다 — 미국의 하루 등락률과 일봉이 정규장 종가 기준이다.
 * 16:01이면 애프터마켓이 1분 흐른 뒤라, 실적 발표가 16:00 정각에 나온 날엔 그 1분의 움직임이 섞일 수 있다.
 * 해외를 KST로 걸지 않는다 — 05:01 고정이면 서머타임이 끝난 뒤(11월) 정규장 중에 찍힌다.
 */
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

    /**
     * 거래일은 **현지 날짜**다 — 16:01 뉴욕은 KST로 다음 날 새벽이라 한국 날짜를 쓰면 하루 밀린다.
     * 휴장일은 건너뛰지 않고 **휴장으로 남긴다** — 캘린더가 빈 칸과 휴장을 가를 수 있게.
     */
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
