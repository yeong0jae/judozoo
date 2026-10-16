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

/**
 * 왜 오르나 — 매분 확인. 만들지 말지·무엇을 만들지는 도메인([judozoo.insight.domain.Schedule.pick])이 정한다.
 *
 * 국내는 KST 08~20시, 해외는 뉴욕 08~18시에만 깨어난다(생성 시간 밖의 분은 [InsightService.run]이 바로 끝낸다).
 * 해외를 KST로 걸지 않는다 — 서머타임이 끝나면(11월) 한 시간 어긋난다.
 *
 * 한 번에 5종목을 만들면 1분을 넘긴다. 앞 실행이 끝나야 다음 분이 잡히므로 겹쳐 돌지 않는다 — 정해진 시각을
 * 5분 폭으로 보고 30분 규칙이 중복을 막아 정각을 놓치지 않는다.
 */
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
