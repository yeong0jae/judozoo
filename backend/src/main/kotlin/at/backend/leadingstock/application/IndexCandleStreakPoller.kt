package at.backend.leadingstock.application

import at.backend.leadingstock.domain.IndexMinuteCandles
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.library.time.TimeProvider
import at.backend.market.application.MarketStatusService
import at.backend.platform.kiwoom.client.KiwoomIndexClient
import at.backend.stock.domain.Market
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 장중 주기적으로 코스피·코스닥 지수 1분봉을 합성해, 같은 색(양봉/음봉) 4연속이 처음 달성된 순간만 적재한다.
 * 같은 연속 구간(첫 봉 시각이 같음)에선 5·6연속이 이어져도 재발화하지 않고, 색이 끊긴 새 구간에서 다시 4연속이면 발화.
 * 시장별 마지막 발화 구간 시작 시각을 메모리에 들고(일자 바뀌면 초기화), 휴장/장외엔 스킵. 테스트에선 제외.
 */
@Component
@Profile("!test")
class IndexCandleStreakPoller(
    private val indexClient: KiwoomIndexClient,
    private val marketSignalEventService: MarketSignalEventService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}
    private val lastFiredRunStart = ConcurrentHashMap<Market, LocalDateTime>()
    private val tradeDate = AtomicReference<LocalDate?>(null)

    @Scheduled(fixedDelayString = "\${leading-stock.market-signal.candle-poll-interval-millis:30000}")
    fun onSchedule() {
        val status = marketStatusService.getStatus()
        if (status.isHoliday || !status.tradingHoursOpen) return
        runCatching { detect() }.onFailure { log.warn(it) { "지수 캔들 연속 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) lastFiredRunStart.clear()

        val now = timeProvider.now()
        val recorded = Market.entries.mapNotNull { market ->
            val ticks = indexClient.fetchIndexTicks(market, today)
            val streak = IndexMinuteCandles.fromTicks(ticks).trailingStreak(excludeMinute = now)
            if (streak == null || streak.count < STREAK_THRESHOLD) return@mapNotNull null
            // 같은 연속 구간(첫 봉 시각 동일)에 대해선 한 번만 발화
            if (lastFiredRunStart[market] == streak.startMinute) return@mapNotNull null
            lastFiredRunStart[market] = streak.startMinute
            MarketSignalEvent.candleStreak(
                occurredAt = now,
                tradeDate = today,
                market = market,
                side = streak.side,
                streak = streak.count,
            )
        }

        marketSignalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "지수 캔들 연속 ${recorded.size}건 적재" }
    }

    companion object {
        private const val STREAK_THRESHOLD = 4 // 양봉/음봉 4연속
    }
}
