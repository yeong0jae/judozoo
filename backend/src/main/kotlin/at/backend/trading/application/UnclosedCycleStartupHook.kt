package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.event.TradingCycleClosed
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class UnclosedCycleStartupHook(
    private val cycleRepository: TradingCycleJpaRepository,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun closeUnclosedCycles() {
        val active = cycleRepository.findByStatusIn(TradingCycleStatus.OPEN)
        if (active.isEmpty()) return
        val now = timeProvider.now()
        for (cycle in active) {
            cycle.close(CloseReason.UNCLOSED, now)
        }
        cycleRepository.saveAll(active)
        val instant = now.toInstantKst()
        for (cycle in active) {
            eventPublisher.publishEvent(
                TradingCycleClosed(
                    cycleId = cycle.id,
                    closeReason = CloseReason.UNCLOSED.name,
                    ts = instant,
                )
            )
        }
        log.warn("재기동 시 활성 사이클 일괄 마감 — count={}", active.size)
    }
}
