package at.backend.overseasleadingstock.application

import at.backend.leadingstock.domain.SignalEventType
import at.backend.overseasleadingstock.domain.OverseasSignalEvent
import at.backend.overseasleadingstock.infrastructure.repository.OverseasSignalEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 해외 시그널 전이 적재/조회. 적재는 폴러가, 조회는 실시간 로그가 쓴다. */
@Service
class OverseasSignalEventService(
    private val repository: OverseasSignalEventRepository,
) {

    @Transactional
    fun recordAll(events: List<OverseasSignalEvent>) {
        if (events.isNotEmpty()) repository.saveAll(events)
    }

    @Transactional(readOnly = true)
    fun eventsOn(date: LocalDate): List<OverseasSignalEvent> =
        repository.findByTradeDateOrderByOccurredAtDesc(date)

    /** 그날 같은 종목·타입 전이가 이미 적재됐는지 — 재시작 재발화 방지. */
    @Transactional(readOnly = true)
    fun alreadyFired(date: LocalDate, exchange: String, symbol: String, type: SignalEventType): Boolean =
        repository.existsByTradeDateAndExchangeAndSymbolAndEventType(date, exchange, symbol, type)
}
