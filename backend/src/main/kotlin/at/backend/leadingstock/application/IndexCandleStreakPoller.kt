package at.backend.leadingstock.application

import at.backend.leadingstock.domain.IndexMinuteCandles
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.domain.NetTradeSide
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
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 장중 주기적으로 코스피·코스닥 지수 1분봉을 합성해, 같은 색(양봉/음봉)이 5·10·15연속…
 * (5의 배수에 처음 도달한 순간)만 적재한다. 발화 숫자는 항상 배수(5·10·15)로 박는다.
 *
 * 디바운스는 시장별 (방향, 마지막 발화 배수)로 한다: 같은 방향이 길어지는 동안엔 더 큰 배수를 넘을 때만 발화하고,
 * 방향이 바뀌면 0으로 리셋해 다시 5부터. 이 상태는 메모리에 두되 **그날 DB의 캔들 시그널에서 복원**하므로
 * 재시작해도 같은 연속을 다시 울리지 않는다. 휴장/장외엔 스킵, 테스트에선 제외.
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
    private val states = ConcurrentHashMap<Market, StreakFireState>()
    private val tradeDate = AtomicReference<LocalDate?>(null)
    private var seeded = false
    private val sessionStart: LocalTime = LocalTime.parse(sessionStart)
    private val sessionEnd: LocalTime = LocalTime.parse(sessionEnd)

    /** 한 시장의 디바운스 상태 — [side] 현재 연속 방향, [firedMultiple] 마지막으로 발화한 5의 배수. */
    private data class StreakFireState(val side: NetTradeSide, val firedMultiple: Int)

    @Scheduled(fixedDelayString = "\${leading-stock.market-signal.candle-poll-interval-millis:30000}")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val now = timeProvider.now().toLocalTime()
        if (now < sessionStart || now > sessionEnd) return // 지수는 정규장에만 체결
        runCatching { detect() }.onFailure { log.warn(it) { "지수 캔들 연속 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) {
            states.clear()
            seeded = false
        }
        if (!seeded) {
            seedFromDb(today) // 재시작 복원 — 그날 이미 발화한 배수를 DB에서 되살린다
            seeded = true
        }

        val now = timeProvider.now()
        val recorded = Market.entries.mapNotNull { market ->
            val intraday = indexClient.fetchIndexIntraday(market, today) ?: return@mapNotNull null

            // 매 폴마다 받은 최근 ~4분치를 저장소에 누적(겹치며 하루를 채움). 백필 없음 — 한도 보호.
            candleStore.merge(market, IndexMinuteCandles.fromTicks(intraday.ticks).candles())

            // 연속 판정은 누적 저장소 기준 — 4분 페이지 경계에서 연속이 잘리지 않게.
            val streak = IndexMinuteCandles(candleStore.candles(market)).trailingStreak(excludeMinute = now)
                ?: return@mapNotNull null

            val multiple = streak.count / STREAK_STEP * STREAK_STEP // 5~9→5, 10~14→10, …
            // 방향이 그대로면 직전 발화 배수 이어받고, 바뀌면 0부터.
            val firedMultiple = states[market]?.takeIf { it.side == streak.side }?.firedMultiple ?: 0
            if (multiple < STREAK_STEP || multiple <= firedMultiple) {
                states[market] = StreakFireState(streak.side, firedMultiple) // 방향만 갱신(발화 없음)
                return@mapNotNull null
            }

            states[market] = StreakFireState(streak.side, multiple)
            MarketSignalEvent.candleStreak(
                occurredAt = now,
                tradeDate = today,
                market = market,
                side = streak.side,
                streak = multiple, // 표시는 항상 배수(5·10·15)
                indexValue = intraday.value,
                changeRate = intraday.changeRate,
            )
        }

        marketSignalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "지수 캔들 연속 ${recorded.size}건 적재" }
    }

    /** 그날 이미 적재된 시장별 최신 캔들 시그널로 디바운스 상태를 복원(재시작 대비). */
    private fun seedFromDb(today: LocalDate) {
        Market.entries.forEach { market ->
            val last = marketSignalEventService.latestCandleStreak(market, today) ?: return@forEach
            val mult = last.streak ?: return@forEach
            states[market] = StreakFireState(last.side, mult)
        }
    }

    companion object {
        private const val STREAK_STEP = 5 // 5연속마다(5·10·15…) 발화
    }
}
