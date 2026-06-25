package at.backend.leadingstock.application

import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.infrastructure.repository.MarketSignalEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 시장 단위 시그널 전이 적재/조회. 적재는 폴러가, 조회는 실시간 로그가 쓴다. */
@Service
class MarketSignalEventService(
    private val repository: MarketSignalEventRepository,
) {

    @Transactional
    fun recordAll(events: List<MarketSignalEvent>) {
        if (events.isNotEmpty()) repository.saveAll(events)
    }

    @Transactional(readOnly = true)
    fun eventsOn(date: LocalDate): List<MarketSignalEvent> =
        repository.findByTradeDateOrderByOccurredAtDesc(date)
}
