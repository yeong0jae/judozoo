package at.backend.leadingstock.application

import at.backend.leadingstock.domain.SignalEvent
import at.backend.leadingstock.domain.SignalEventType
import at.backend.leadingstock.domain.SignalReading
import at.backend.leadingstock.domain.SignalState
import at.backend.library.time.TimeProvider
import at.backend.market.application.MarketStatusService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 장중 주기적으로 주도주 후보의 시그널을 읽어 직전 상태와 비교, 전이가 일어난 순간만 적재한다.
 * 화면을 아무도 안 보고 있어도 쌓이게 하려고 서버가 능동적으로 돈다. 휴장/장외 시간엔 스킵.
 * 종목별 직전 상태는 메모리에 들고(일자 바뀌면 초기화), 테스트에선 네트워크를 안 때리도록 제외.
 */
@Component
@Profile("!test")
class SignalEventPoller(
    private val leadingStockService: LeadingStockService,
    private val signalEventService: SignalEventService,
    private val marketStatusService: MarketStatusService,
    @Value("\${leading-stock.signal-event.min-change-rate:-7.0}")
    private val minChangeRate: Double,
    @Value("\${leading-stock.signal-event.cooldown-minutes:3}")
    private val cooldownMinutes: Long,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}
    private val states = ConcurrentHashMap<String, SignalState>()
    // "종목|타입" → 마지막 적재 시각. 프리마켓 출렁임 등으로 같은 전이가 짧은 간격에 반복 적재되는 걸 막는다.
    private val lastFired = ConcurrentHashMap<String, LocalDateTime>()
    private val tradeDate = AtomicReference<LocalDate?>(null)

    @Scheduled(fixedDelayString = "\${leading-stock.signal-event.poll-interval-millis:10000}")
    fun onSchedule() {
        val status = marketStatusService.getStatus()
        if (status.isHoliday || !status.tradingHoursOpen) return
        runCatching { detect() }.onFailure { log.warn(it) { "시그널 전이 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) {
            states.clear() // 일자 전환 — 직전 상태 폐기
            lastFired.clear()
        }

        val now = timeProvider.now()
        val cooldownFrom = now.minusMinutes(cooldownMinutes)
        val recorded = leadingStockService.signalReadings(minChangeRate)
            .flatMap { r ->
                val prev = states[r.stockCode] ?: SignalState.INITIAL
                val (events, next) = prev.advance(SignalReading(r.gapRate, r.peakPrice, r.spikeRatio))
                states[r.stockCode] = next
                if (events.isEmpty()) return@flatMap emptyList()
                val theme = leadingStockService.themesOf(r.stockCode).firstOrNull()
                events.mapNotNull { type ->
                    // 같은 종목·타입이 쿨다운(기본 3분) 안에 또 뜨면 중복으로 보고 스킵
                    val key = "${r.stockCode}|$type"
                    if (lastFired[key]?.isAfter(cooldownFrom) == true) return@mapNotNull null
                    lastFired[key] = now
                    toEvent(type, r, now, today, theme)
                }
            }

        signalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "시그널 전이 ${recorded.size}건 적재" }
    }

    private fun toEvent(
        type: SignalEventType,
        r: CandidateSignalReading,
        now: java.time.LocalDateTime,
        today: LocalDate,
        theme: String?,
    ) = SignalEvent(
        occurredAt = now,
        tradeDate = today,
        stockCode = r.stockCode,
        stockName = r.stockName,
        eventType = type,
        currentPrice = r.currentPrice,
        priceChangeRate = r.priceChangeRate,
        tradingValue = r.tradingValue,
        gapRate = if (type == SignalEventType.VOLUME_SPIKE) null else r.gapRate,
        spikeRatio = if (type == SignalEventType.VOLUME_SPIKE) r.spikeRatio else null,
        theme = theme,
    )
}
