package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.toss.client.TossMarketIndicatorClient
import at.backend.stock.domain.Market
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 장중 주기적으로 코스피·코스닥의 당일 누적 투자자 매매대금(토스)을 받아 스냅샷으로 적재한다.
 * 세션(오전/오후/막판) diff의 원천 시계열이자, 토스 today 레코드의 장중 갱신주기 확인용. 테스트 제외.
 */
@Component
@Profile("!test")
class MarketInvestorPoller(
    private val client: TossMarketIndicatorClient,
    private val service: MarketInvestorService,
    private val timeProvider: TimeProvider,
    @Value("\${toss.investor.session-start:09:00}") sessionStart: String,
    @Value("\${toss.investor.session-end:15:40}") sessionEnd: String,
) {
    private val log = KotlinLogging.logger {}
    private val sessionStart: LocalTime = LocalTime.parse(sessionStart)
    private val sessionEnd: LocalTime = LocalTime.parse(sessionEnd)

    @Scheduled(fixedDelayString = "\${toss.investor.poll-interval-millis:60000}")
    fun onSchedule() {
        val now = timeProvider.now()
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return
        val t = now.toLocalTime()
        if (t < sessionStart || t > sessionEnd) return
        runCatching {
            Market.entries.forEach { market ->
                val today = client.fetchInvestorTrading(market.name, interval = "1d", count = 1).firstOrNull()
                if (today != null && today.date == now.toLocalDate()) {
                    service.recordSnapshot(market, now, today)
                }
            }
        }.onFailure { log.warn(it) { "시장 투자자 스냅샷 적재 실패" } }
    }
}
