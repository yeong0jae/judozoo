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
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 장중 주기적으로 코스피·코스닥 지수 1분봉을 합성해, 5분봉 20이평 상향 돌파(반등)·하향 돌파(꺾임)를 적재한다.
 *
 * 반등/꺾임은 각자 무장 상태에서 해당 방향 돌파봉이 나올 때 1회 발화하고 해제한다. 반대로 이평 대비 마진 이상
 * 밀려야(반등은 아래로, 꺾임은 위로) 재무장해(잔떨림 차단) 다음 돌파를 다시 잡는다. 무장 상태는 메모리에 두고
 * 일자가 바뀌면 초기화한다. 휴장/장외엔 스킵, 테스트에선 제외.
 */
@Component
@Profile("!test")
class IndexReboundPoller(
    private val indexClient: KiwoomIndexClient,
    private val candleStore: IndexMinuteCandleStore,
    private val marketSignalEventService: MarketSignalEventService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
    @Value("\${leading-stock.market-signal.session-start:09:00}") sessionStart: String,
    @Value("\${leading-stock.market-signal.session-end:15:30}") sessionEnd: String,
) {
    private val log = KotlinLogging.logger {}
    private val reboundArmed = ConcurrentHashMap<Market, Boolean>() // 시장별 반등 무장 상태(발화 후 해제, 이평 아래로 눌리면 재무장)
    private val breakdownArmed = ConcurrentHashMap<Market, Boolean>() // 시장별 꺾임 무장 상태(발화 후 해제, 이평 위로 오르면 재무장)
    private val tradeDate = AtomicReference<LocalDate?>(null)
    private val sessionStart: LocalTime = LocalTime.parse(sessionStart)
    private val sessionEnd: LocalTime = LocalTime.parse(sessionEnd)

    @Scheduled(fixedDelayString = "\${leading-stock.market-signal.candle-poll-interval-millis:30000}")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val now = timeProvider.now().toLocalTime()
        if (now < sessionStart || now > sessionEnd) return // 지수는 정규장에만 체결
        runCatching { detect() }.onFailure { log.warn(it) { "지수 반등 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) { // 일자 전환 — 무장 상태 초기화
            reboundArmed.clear()
            breakdownArmed.clear()
        }

        val now = timeProvider.now()
        val recorded = Market.entries.flatMap { market ->
            val intraday = indexClient.fetchIndexIntraday(market, today) ?: return@flatMap emptyList()

            // 매 폴마다 받은 최근 ~4분치를 저장소에 누적(겹치며 하루를 채움). 백필 없음 — 한도 보호.
            candleStore.merge(market, IndexMinuteCandles.fromTicks(intraday.ticks).candles())
            // 누적 저장소 기준 판정 — 4분 페이지 경계에서 이평이 잘리지 않게.
            val candles = IndexMinuteCandles(candleStore.candles(market))

            val ma = candles.movingAverage(MA_INTERVAL_MINUTES, MA_PERIOD, MA_REARM_MARGIN)

            // 반등 — 5분봉 20이평 상향 돌파봉 + 무장 상태에서 1회. 이평 아래로 마진 이상 눌려야 재무장(잔떨림 차단).
            val reboundEvent = ma?.let {
                val armed = reboundArmed[market] ?: true
                if (armed && it.crossedUp) {
                    reboundArmed[market] = false
                    MarketSignalEvent.ma20Rebound(now, today, market, intraday.value, intraday.changeRate)
                } else {
                    if (it.belowBand) reboundArmed[market] = true
                    null
                }
            }

            // 꺾임 — 5분봉 20이평 하향 돌파봉 + 무장 상태에서 1회. 이평 위로 마진 이상 올라야 재무장(잔떨림 차단).
            val breakdownEvent = ma?.let {
                val armed = breakdownArmed[market] ?: true
                if (armed && it.crossedDown) {
                    breakdownArmed[market] = false
                    MarketSignalEvent.ma20Breakdown(now, today, market, intraday.value, intraday.changeRate)
                } else {
                    if (it.aboveBand) breakdownArmed[market] = true
                    null
                }
            }

            listOfNotNull(reboundEvent, breakdownEvent)
        }

        marketSignalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "지수 반등·꺾임 ${recorded.size}건 적재" }
    }

    companion object {
        private const val MA_INTERVAL_MINUTES = 5   // 반등 판정 분봉 주기
        private const val MA_PERIOD = 20            // 반등 판정 이평 기간(봉)
        private const val MA_REARM_MARGIN = 0.005   // 반등 재무장 마진(0.5%)
    }
}
