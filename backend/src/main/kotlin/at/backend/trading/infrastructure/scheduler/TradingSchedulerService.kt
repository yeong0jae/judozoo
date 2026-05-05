package at.backend.trading.infrastructure.scheduler

import at.backend.library.time.TimeProvider
import at.backend.market.domain.event.HolidayChanged
import at.backend.platform.kis.client.KisRestClient
import at.backend.trading.application.CommandGate
import at.backend.trading.application.CycleOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZoneId

@Component
class TradingSchedulerService(
    private val cycleOrchestrator: CycleOrchestrator,
    private val commandGate: CommandGate,
    private val kisRestClient: KisRestClient,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    fun routeMarketCloseSignal() {
        log.info("15:20 강제 청산 트리거")
        cycleOrchestrator.broadcastMarketClose()
    }

    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    fun toggleCommandGate() {
        val today = timeProvider.now().toLocalDate()
        val isBusinessDay = runCatching {
            kisRestClient.checkHoliday(today).output.firstOrNull()?.bzdyYn == "Y"
        }.getOrElse {
            log.warn("영업일 검증 실패 — 게이트 닫힘 유지", it)
            commandGate.close()
            return
        }
        val instant = timeProvider.now().atZone(KST).toInstant()
        if (isBusinessDay) {
            commandGate.open()
            eventPublisher.publishEvent(HolidayChanged(isHoliday = false, ts = instant))
            log.info("영업일 — 명령 접수 게이트 OPEN")
        } else {
            commandGate.close()
            eventPublisher.publishEvent(HolidayChanged(isHoliday = true, ts = instant))
            log.info("휴장일 — 명령 접수 게이트 CLOSED")
        }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
