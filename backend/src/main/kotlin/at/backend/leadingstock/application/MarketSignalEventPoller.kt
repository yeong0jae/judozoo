package at.backend.leadingstock.application

import at.backend.leadingstock.domain.InvestorNetBuyState
import at.backend.leadingstock.domain.InvestorType
import at.backend.leadingstock.domain.MarketSignalEvent
import at.backend.leadingstock.domain.MarketSignalThresholds
import at.backend.library.time.TimeProvider
import at.backend.market.application.MarketStatusService
import at.backend.platform.kiwoom.client.KiwoomSectorInvestorClient
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
 * 장중 주기적으로 코스피·코스닥의 투자자별 당일 누적 순매수를 읽어, 단계(조/천억)를 넘나든 순간만 적재한다.
 * (시장·투자자)별 직전 단계를 메모리에 들고(일자 바뀌면 초기화), 휴장/장외·정규장 밖이면 스킵. 테스트에선 제외.
 */
@Component
@Profile("!test")
class MarketSignalEventPoller(
    private val sectorInvestorClient: KiwoomSectorInvestorClient,
    private val marketSignalEventService: MarketSignalEventService,
    private val marketStatusService: MarketStatusService,
    private val timeProvider: TimeProvider,
    @Value("\${leading-stock.market-signal.session-start:09:00}") sessionStart: String,
    @Value("\${leading-stock.market-signal.session-end:15:30}") sessionEnd: String,
) {
    private val log = KotlinLogging.logger {}
    private val states = ConcurrentHashMap<String, InvestorNetBuyState>()
    private val tradeDate = AtomicReference<LocalDate?>(null)
    private val sessionStart: LocalTime = LocalTime.parse(sessionStart)
    private val sessionEnd: LocalTime = LocalTime.parse(sessionEnd)

    @Scheduled(fixedDelayString = "\${leading-stock.market-signal.poll-interval-millis:30000}")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val now = timeProvider.now().toLocalTime()
        if (now < sessionStart || now > sessionEnd) return // 지수는 정규장에만 체결
        runCatching { detect() }.onFailure { log.warn(it) { "시장 시그널 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) states.clear() // 일자 전환 — 직전 단계 폐기

        val now = timeProvider.now()
        val recorded = Market.entries.flatMap { market ->
            val snapshot = sectorInvestorClient.fetchSectorNetBuy(market.mrktTp()) ?: return@flatMap emptyList()
            val step = MarketSignalThresholds.stepEok(market)
            val buffer = MarketSignalThresholds.bufferEok(market)

            netBuyByInvestor(snapshot).mapNotNull { (investor, netEok) ->
                val key = "$market|$investor"
                val (transition, next) = (states[key] ?: InvestorNetBuyState.INITIAL)
                    .advance(netEok, step, buffer)
                states[key] = next
                transition?.let {
                    MarketSignalEvent.netBuyLevel(
                        occurredAt = now,
                        tradeDate = today,
                        market = market,
                        investor = investor,
                        side = it.side,
                        level = it.level,
                        netAmountEok = netEok,
                        indexValue = snapshot.indexValue,
                        changeRate = snapshot.changeRate,
                    )
                }
            }
        }

        marketSignalEventService.recordAll(recorded)
        if (recorded.isNotEmpty()) log.info { "시장 시그널 ${recorded.size}건 적재" }
    }

    private fun netBuyByInvestor(s: KiwoomSectorInvestorClient.SectorInvestorNetBuy) = listOf(
        InvestorType.FOREIGN to s.foreignEok,
        InvestorType.INSTITUTION to s.institutionEok,
        InvestorType.INDIVIDUAL to s.individualEok,
    )

    private fun Market.mrktTp() = when (this) {
        Market.KOSPI -> "0"
        Market.KOSDAQ -> "1"
    }
}
