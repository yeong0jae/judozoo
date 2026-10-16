package judozoo.market.application

import judozoo.library.KST
import judozoo.market.domain.Region
import judozoo.platform.kis.KisFutures
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

@Component
class FuturesInvestorScheduler(
    private val futures: KisFutures,
    private val investors: MarketInvestors,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun poll() {
        if (calendar.isHoliday(Region.KR)) return
        val at = LocalDateTime.now(clock.withZone(KST))
        if (at.toLocalTime().isBefore(FUTURES_START) || at.toLocalTime().isAfter(FUTURES_END)) return
        for (market in Market.entries) {
            val net = futures.fetchInvestors(market) ?: continue
            try {
                investors.recordFutures(market, at.toLocalDate(), at, net)
            } catch (e: Exception) {
                log.warn("선물 투자자 스냅샷 적재 실패 (market={})", market, e)
            }
        }
    }

    companion object {
        private val FUTURES_START = LocalTime.of(8, 45)
        private val FUTURES_END = LocalTime.of(15, 45)
    }
}
