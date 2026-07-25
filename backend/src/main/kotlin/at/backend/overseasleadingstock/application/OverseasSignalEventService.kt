package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.domain.OverseasSignalEvent
import at.backend.overseasleadingstock.infrastructure.repository.OverseasSignalEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 해외 시그널 전이 적재 — 폴러가 감지한 전이를 저장한다. */
@Service
class OverseasSignalEventService(
    private val repository: OverseasSignalEventRepository,
) {

    @Transactional
    fun recordAll(events: List<OverseasSignalEvent>) {
        if (events.isNotEmpty()) repository.saveAll(events)
    }
}
