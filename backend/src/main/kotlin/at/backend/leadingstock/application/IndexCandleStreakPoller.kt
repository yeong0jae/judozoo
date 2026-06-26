package at.backend.leadingstock.application

import at.backend.leadingstock.domain.IndexMinuteCandles
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.library.time.TimeProvider
import at.backend.market.application.MarketStatusService
import at.backend.platform.kiwoom.client.KiwoomIndexClient
import at.backend.stock.domain.Market
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
 * 장중 주기적으로 코스피·코스닥 지수 1분봉을 합성해, 같은 색(양봉/음봉)이 5연속·10연속·15연속…
 * (5의 배수마다) 도달한 순간을 적재한다. 같은 연속 구간에서 6~9연속처럼 같은 블록 안에선 재발화하지 않고,
 * 다음 5단위 블록을 넘거나 색이 끊겨 새 구간이 시작되면 다시 발화.
 * 시장별 (구간 시작 시각, 마지막 발화 블록)을 메모리에 들고(일자 바뀌면 초기화), 휴장/장외엔 스킵. 테스트에선 제외.
 */
@Component
@Profile("!test")
class IndexCandleStreakPoller(
    private val indexClient: KiwoomIndexClient,
    private val candleStore: IndexMinuteCandleStore,
    private val marketSignalEventService: MarketSignalEventService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
    @Value("\${leading-stock.market-signal.session-start:09:00}") sessionStart: String,
    @Value("\${leading-stock.market-signal.session-end:15:30}") sessionEnd: String,
) {
    private val log = KotlinLogging.logger {}
    private val runStates = ConcurrentHashMap<Market, RunState>()
    private val tradeDate = AtomicReference<LocalDate?>(null)
    private val sessionStart: LocalTime = LocalTime.parse(sessionStart)
    private val sessionEnd: LocalTime = LocalTime.parse(sessionEnd)

    /** 한 시장의 직전 연속 구간 추적 — [startMinute] 구간 첫 봉, [firedBlock] 마지막으로 발화한 4단위 블록. */
    private data class RunState(val startMinute: LocalDateTime, val firedBlock: Int)

    @Scheduled(fixedDelayString = "\${leading-stock.market-signal.candle-poll-interval-millis:30000}")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val now = timeProvider.now().toLocalTime()
        if (now < sessionStart || now > sessionEnd) return // 지수는 정규장에만 체결
        runCatching { detect() }.onFailure { log.warn(it) { "지수 캔들 연속 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) runStates.clear()

        val now = timeProvider.now()
        val recorded = Market.entries.mapNotNull { market ->
            val intraday = indexClient.fetchIndexIntraday(market, today) ?: return@mapNotNull null

            // 매 폴마다 받은 최근 ~4분치를 저장소에 누적(겹치며 하루를 채움). 백필 없음 — 한도 보호.
            candleStore.merge(market, IndexMinuteCandles.fromTicks(intraday.ticks).candles())

            // 연속 판정은 누적 저장소 기준 — 4분 페이지 경계에서 연속이 잘리지 않게.
            val streak = IndexMinuteCandles(candleStore.candles(market)).trailingStreak(excludeMinute = now)
                ?: return@mapNotNull null

            val block = streak.count / STREAK_STEP // 5~9→1, 10~14→2, … (5의 배수 블록)
            if (block < 1) return@mapNotNull null
            // 같은 구간(첫 봉 시각 동일)이면 직전 발화 블록 이어받고, 새 구간이면 0부터.
            val prev = runStates[market]
            val firedBlock = if (prev?.startMinute == streak.startMinute) prev.firedBlock else 0
            if (block <= firedBlock) return@mapNotNull null

            runStates[market] = RunState(streak.startMinute, block)
            MarketSignalEvent.candleStreak(
                occurredAt = now,
                tradeDate = today,
                market = market,
                side = streak.side,
                streak = streak.count,
                indexValue = intraday.value,
                changeRate = intraday.changeRate,
            )
        }

        marketSignalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "지수 캔들 연속 ${recorded.size}건 적재" }
    }

    companion object {
        private const val STREAK_STEP = 5 // 5연속마다(5·10·15…) 발화
    }
}
