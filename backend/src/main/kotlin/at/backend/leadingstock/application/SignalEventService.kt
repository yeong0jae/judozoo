package at.backend.leadingstock.application

import at.backend.leadingstock.domain.SignalEvent
import at.backend.leadingstock.infrastructure.repository.SignalEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 시그널 전이 적재/조회. 적재는 폴러가, 조회는 라이브 피드·종목 여정 화면이 쓴다. */
@Service
class SignalEventService(
    private val repository: SignalEventRepository,
) {

    @Transactional
    fun recordAll(events: List<SignalEvent>) {
        if (events.isNotEmpty()) repository.saveAll(events)
    }

    @Transactional(readOnly = true)
    fun eventsOn(date: LocalDate): List<SignalEvent> =
        repository.findByTradeDateOrderByOccurredAtDesc(date)
}
