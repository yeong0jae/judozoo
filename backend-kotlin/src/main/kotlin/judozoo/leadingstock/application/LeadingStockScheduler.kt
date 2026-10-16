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

/**
 * 국내 주도주 폴러 — 후보 풀 갱신(15초) / 감시 풀 당일 분봉(20초) / 종목 시그널(10초) / 시장 수급(1분) / 지수 반등(30초).
 *
 * 폴러는 **화면을 아무도 안 보고 있어도** 쌓이게 하려고 서버가 능동적으로 돈다.
 *
 * - 후보 풀·당일 분봉은 **Python에서도 돈다**(028) — 메모리 상태라 Python의 타임라인·종가베팅이 자기 풀을 읽는다.
 * - 시그널 세 개(DB에 쓴다)는 **여기서만** 돈다. Python 쪽 등록은 같은 커밋에서 껐다.
 * - **분봉 마감 확정(20:01, DB에 쓴다)은 Python만** 돈다 — 여기서는 확정본을 DB에서 읽는다.
 *
 * 실패는 삼키되 [JobFailures]로 센다 — 잡 이름은 Python과 같다.
 */
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

    /** 실패해도 기존 값은 그대로 쓰인다. 다음 회차가 다시 시도한다. */
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

    /** 종목 하나가 실패하면 그 종목만 기존 봉을 쓴다. */
    @Scheduled(fixedDelay = 20_000)
    fun syncTodayMinutes() {
        if (!inSession()) return
        try {
            // 실패는 종목 수와 상관없이 회차당 한 번 센다
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

    /** 프리마켓 개장(08:00)부터 애프터마켓 마감(20:00)까지 — 시황의 세션 표가 정규장 밖 스냅샷도 쓴다. */
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

    /** 지수는 정규장에만 체결된다. */
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

    /** 휴장이 아니고 [start]~[end](양끝 포함) 안인가. */
    private fun between(start: LocalTime, end: LocalTime): Boolean {
        if (calendar.isHoliday(Region.KR)) return false
        val now = LocalTime.now(clock.withZone(KST))
        return !now.isBefore(start) && !now.isAfter(end)
    }

    companion object {
        const val POOL_REFRESH_JOB = "trading-value-pool-refresher"
        const val MINUTE_SYNC_JOB = "today-minute-syncer"
        const val SIGNAL_JOB = "signal-event-poller"
        const val MARKET_FLOW_JOB = "market-flow-poller"
        const val INDEX_REBOUND_JOB = "index-rebound-poller"

        private val SNAPSHOT_START = LocalTime.of(8, 0)
        private val SNAPSHOT_END = LocalTime.of(20, 0)
    }
}
