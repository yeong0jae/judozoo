package at.backend.overseasleadingstock.infrastructure.repository

import at.backend.leadingstock.domain.SignalEventType
import at.backend.overseasleadingstock.domain.OverseasSignalEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface OverseasSignalEventRepository : JpaRepository<OverseasSignalEvent, Long> {

    fun findByTradeDateOrderByOccurredAtDesc(tradeDate: LocalDate): List<OverseasSignalEvent>

    /** 재시작으로 메모리 상태가 초기화돼도 같은 전이가 재적재되지 않도록 — 그날 같은 종목·타입 존재 여부. */
    fun existsByTradeDateAndExchangeAndSymbolAndEventType(
        tradeDate: LocalDate,
        exchange: String,
        symbol: String,
        eventType: SignalEventType,
    ): Boolean
}
