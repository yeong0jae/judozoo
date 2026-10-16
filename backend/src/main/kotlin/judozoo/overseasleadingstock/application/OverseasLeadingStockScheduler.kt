package judozoo.overseasleadingstock.application

import judozoo.library.JobFailures
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OverseasLeadingStockScheduler(
    private val service: OverseasLeadingStockService,
    private val calendar: MarketCalendar,
    private val failures: JobFailures,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 15_000)
    fun refreshTradingValuePool() {
        if (!calendar.usMarketStatus().inSession) return
        try {
            service.refreshTradingValuePool()
        } catch (e: Exception) {
            log.warn("해외 후보 풀 갱신 실패", e)
            failures.record(POOL_REFRESH_JOB)
        }
    }

    @Scheduled(cron = "0 1 20 * * MON-FRI", zone = "America/New_York")
    fun settleMinutes() {
        if (calendar.isHoliday(Region.US)) return
        try {
            val failed = service.settleMinutes()
            if (failed > 0) {
                log.warn("해외 분봉 마감 확정 — {}종목 실패", failed)
                failures.record(MINUTE_SETTLE_JOB)
            }
        } catch (e: Exception) {
            log.warn("해외 분봉 마감 확정 실패", e)
            failures.record(MINUTE_SETTLE_JOB)
        }
    }

    companion object {
        const val POOL_REFRESH_JOB = "overseas-trading-value-pool-refresher"
        const val MINUTE_SETTLE_JOB = "overseas-minute-settler"
    }
}
