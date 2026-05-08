package at.backend.trading.infrastructure.scheduler

import at.backend.trading.application.CycleOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class MarketCloseScheduler(
    private val cycleOrchestrator: CycleOrchestrator,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    fun routeMarketCloseSignal() {
        log.info("15:20 강제 청산 트리거")
        cycleOrchestrator.broadcastMarketClose()
    }
}
