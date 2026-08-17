package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisFuturesClient
import at.backend.stock.domain.Market
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime

/**
 * 장중 주기적으로 코스피200·코스닥150 선물의 투자자별 당일 누적 순매수를 읽어 시장별 스냅샷으로 적재한다.
 * 세션(오전/오후/막판) 순매수는 이 스냅샷들의 경계 diff로 계산된다. 휴장/장 밖이면 스킵. 테스트에선 제외.
 */
@Component
@Profile("!test")
class FuturesInvestorPoller(
    private val client: KisFuturesClient,
    private val service: MarketFuturesInvestorService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelay = 60_000)
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val now = timeProvider.now()
        val time = now.toLocalTime()
        if (time < SESSION_START || time > SESSION_END) return
        Market.entries.forEach { market ->
            val investors = client.fetchInvestors(market) ?: return@forEach
            runCatching { service.record(market, timeProvider.today(), now, investors) }
                .onFailure { log.warn(it) { "선물 투자자 스냅샷 적재 실패 (market=$market)" } }
        }
    }

    companion object {
        private val SESSION_START: LocalTime = LocalTime.of(8, 45) // 선물 개장
        private val SESSION_END: LocalTime = LocalTime.of(15, 45) // 선물 마감
    }
}
