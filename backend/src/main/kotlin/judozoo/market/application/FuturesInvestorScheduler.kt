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

/**
 * 선물 투자자 폴러(60초) — 코스피200·코스닥150 선물의 당일 누적 투자자 순매수를 스냅샷으로 찍어 둔다.
 *
 * 세션별 순매수는 이 스냅샷들의 경계 차이로 계산되므로, 폴러가 돌지 않은 과거는 **소급이 불가능**하다.
 * 선물 개장(08:45)부터 마감(15:45)까지, 휴장일은 쉰다.
 */
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
