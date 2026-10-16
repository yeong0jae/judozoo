package judozoo.leadingstock.application

import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalTime

@Component
class LeadingStockScheduler(
    private val service: LeadingStockService,
    private val stockSignals: StockSignalDetector,
    private val marketFlows: MarketFlowDetector,
    private val indexRebound: IndexReboundDetector,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 15_000)
    fun refreshTradingValuePool() {
        if (!inSession()) return
        try {
            service.refreshTradingValuePool()
        } catch (e: Exception) {
            log.warn("거래대금 상위 갱신 실패", e)
            failures.record(POOL_REFRESH_JOB)
        }
    }

    @Scheduled(fixedDelay = 20_000)
    fun syncTodayMinutes() {
        if (!inSession()) return
        try {
            val failed = service.syncTodayMinutes()
            if (failed > 0) {
                log.warn("당일 분봉 갱신 — {}종목 실패", failed)
                failures.record(MINUTE_SYNC_JOB)
            }
        } catch (e: Exception) {
            log.warn("당일 분봉 갱신 실패", e)
            failures.record(MINUTE_SYNC_JOB)
        }
    }

    @Scheduled(cron = "0 1 20 * * MON-FRI", zone = "Asia/Seoul")
    fun settleTodayMinutes() {
        if (calendar.marketStatus().first) return
        try {
            val failed = service.settleTodayMinutes()
            if (failed > 0) {
                log.warn("당일 분봉 마감 확정 — {}종목 실패", failed)
                failures.record(MINUTE_SETTLE_JOB)
            }
        } catch (e: Exception) {
            log.warn("당일 분봉 마감 확정 실패", e)
            failures.record(MINUTE_SETTLE_JOB)
        }
    }

    @Scheduled(fixedDelay = 10_000)
    fun detectStockSignals() {
        if (!inSession()) return
        try {
            stockSignals.detect()
        } catch (e: Exception) {
            log.warn("시그널 전이 적재 실패", e)
            failures.record(SIGNAL_JOB)
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun detectMarketFlows() {
        if (!between(SNAPSHOT_START, SNAPSHOT_END)) return
        try {
            marketFlows.detect()
        } catch (e: Exception) {
            log.warn("시장 시그널 적재 실패", e)
            failures.record(MARKET_FLOW_JOB)
        }
    }

    @Scheduled(fixedDelay = 30_000)
    fun detectIndexRebound() {
        if (!between(MarketFlowDetector.SIGNAL_START, MarketFlowDetector.SIGNAL_END)) return
        try {
            indexRebound.detect()
        } catch (e: Exception) {
            log.warn("지수 반등 적재 실패", e)
            failures.record(INDEX_REBOUND_JOB)
        }
    }

    private fun inSession(): Boolean {
        val (holiday, tradingHours) = calendar.marketStatus()
        return !holiday && tradingHours
    }

    private fun between(start: LocalTime, end: LocalTime): Boolean {
        if (calendar.isHoliday(Region.KR)) return false
        val now = LocalTime.now(clock.withZone(KST))
        return !now.isBefore(start) && !now.isAfter(end)
    }

    companion object {
        const val POOL_REFRESH_JOB = "trading-value-pool-refresher"
        const val MINUTE_SYNC_JOB = "today-minute-syncer"
        const val MINUTE_SETTLE_JOB = "today-minute-settler"
        const val SIGNAL_JOB = "signal-event-poller"
        const val MARKET_FLOW_JOB = "market-flow-poller"
        const val INDEX_REBOUND_JOB = "index-rebound-poller"

        private val SNAPSHOT_START = LocalTime.of(8, 0)
        private val SNAPSHOT_END = LocalTime.of(20, 0)
    }
}
