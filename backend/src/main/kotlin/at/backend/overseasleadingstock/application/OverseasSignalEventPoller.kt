package at.backend.overseasleadingstock.application

import at.backend.leadingstock.domain.SignalEventType
import at.backend.library.time.TimeProvider
import at.backend.overseasleadingstock.domain.OverseasSignalEvent
import at.backend.overseasleadingstock.domain.OverseasSignalReading
import at.backend.overseasleadingstock.domain.OverseasSignalState
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 미국장 시간대(한국 17:00~익일 09:00)에 주기적으로 해외 후보 시그널을 읽어 전이를 적재한다.
 * 분봉은 종목당 1호출(최신 120건)만 받아 스토어에 누적 병합하므로, 장중 내내 돌면 그날치가 쌓인다.
 * 미국 하루 장이 한국 날짜 둘에 걸치므로 세션 기준일(sessionDate)로 상태를 관리한다.
 */
@Component
@Profile("!test")
class OverseasSignalEventPoller(
    private val service: OverseasLeadingStockService,
    private val signalEventService: OverseasSignalEventService,
    private val minuteStore: OverseasMinuteCandleStore,
    @Value("\${overseas-signal-event.min-change-rate:-7.0}")
    private val minChangeRate: Double,
    @Value("\${overseas-signal-event.cooldown-minutes:3}")
    private val cooldownMinutes: Long,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}
    private val states = ConcurrentHashMap<String, OverseasSignalState>()
    private val lastFired = ConcurrentHashMap<String, LocalDateTime>()
    private val sessionDate = AtomicReference<LocalDate?>(null)

    @Scheduled(fixedDelayString = "\${overseas-signal-event.poll-interval-millis:15000}")
    fun onSchedule() {
        val now = timeProvider.now()
        if (!isUsSession(now.toLocalTime())) return
        runCatching { detect(now) }.onFailure { log.warn(it) { "해외 시그널 전이 적재 실패" } }
    }

    private fun detect(now: LocalDateTime) {
        val session = sessionDateOf(now)
        if (sessionDate.getAndSet(session) != session) {
            states.clear()
            lastFired.clear()
            minuteStore.clearBefore(session)
        }

        val cooldownFrom = now.minusMinutes(cooldownMinutes)
        val recorded = service.signalReadings(minChangeRate).flatMap { r ->
            val key = "${r.exchange}:${r.symbol}"
            val prev = states[key] ?: OverseasSignalState.INITIAL
            val (events, next) = prev.advance(OverseasSignalReading(r.gapRate, r.peakPrice, r.spikeRatio))
            states[key] = next
            if (events.isEmpty()) return@flatMap emptyList()
            events.mapNotNull { type ->
                // 돌파·임박은 상태 전이라 재시작 시 재발화 위험 — DB 멱등 가드로 그날 1회만.
                // 스파이크는 본래 여러 번 정상이라 쿨다운(메모리)으로만 제어.
                if (type != SignalEventType.VOLUME_SPIKE &&
                    signalEventService.alreadyFired(session, r.exchange, r.symbol, type)
                ) return@mapNotNull null
                val firedKey = "$key|$type"
                if (lastFired[firedKey]?.isAfter(cooldownFrom) == true) return@mapNotNull null
                lastFired[firedKey] = now
                toEvent(type, r, now, session)
            }
        }

        signalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "해외 시그널 전이 ${recorded.size}건 적재" }
    }

    private fun toEvent(
        type: SignalEventType,
        r: OverseasCandidateReading,
        now: LocalDateTime,
        session: LocalDate,
    ) = OverseasSignalEvent(
        occurredAt = now,
        tradeDate = session,
        exchange = r.exchange,
        symbol = r.symbol,
        name = r.name,
        eventType = type,
        price = r.price,
        rate = r.rate,
        tradingValue = r.tradingValue,
        gapRate = if (type == SignalEventType.VOLUME_SPIKE) null else r.gapRate,
        spikeRatio = if (type == SignalEventType.VOLUME_SPIKE) r.spikeRatio else null,
        minuteTradingValue = if (type == SignalEventType.VOLUME_SPIKE) r.minuteTradingValue else null,
        spikeDirection = if (type == SignalEventType.VOLUME_SPIKE) r.spikeDirection else null,
    )

    /** 미국장 시간대 — 한국 17:00~24:00 또는 00:00~09:00 (프리~애프터). */
    private fun isUsSession(t: LocalTime): Boolean =
        t >= SESSION_START || t < SESSION_END

    /** 세션 기준일 — 낮 12시 전(미국장 후반)은 전날 세션으로 묶는다. */
    private fun sessionDateOf(now: LocalDateTime): LocalDate =
        if (now.toLocalTime() < NOON) now.toLocalDate().minusDays(1) else now.toLocalDate()

    companion object {
        private val SESSION_START = LocalTime.of(17, 0)
        private val SESSION_END = LocalTime.of(9, 0)
        private val NOON = LocalTime.of(12, 0)
    }
}
