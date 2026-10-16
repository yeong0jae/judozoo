package judozoo.closingbet.application

import judozoo.library.JobFailures
import judozoo.library.KST
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * 모의 종가베팅 — 매분 5초에 확인. 20:00 체결과 아침 정산 모두 [ClosingBetService.tick]이 한다.
 * 20시대 내내 돈다 — 일봉 종가가 늦게 오면(027 0단계) 다음 분에 다시 체결을 시도한다.
 */
@Component
class ClosingBetScheduler(private val service: ClosingBetService, private val failures: JobFailures, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "5 * 8-20 * * MON-FRI", zone = "Asia/Seoul")
    fun tick() {
        try {
            service.tick(LocalDateTime.now(clock.withZone(KST)))
        } catch (e: Exception) {
            log.warn("종가베팅 체결·정산 실패", e)
            failures.record(JOB)
        }
    }

    companion object {
        const val JOB = "closingbet-tick"
    }
}
