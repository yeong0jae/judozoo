package at.backend.leadingstock.application

import at.backend.leadingstock.domain.InvestorFlowState
import at.backend.leadingstock.domain.InvestorNetBuyState
import at.backend.leadingstock.domain.InvestorType
import at.backend.leadingstock.domain.MarketInvestorSnapshot
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
    private val flowStates = ConcurrentHashMap<String, InvestorFlowState>()
    private val tradeDate = AtomicReference<LocalDate?>(null)
    // 시그널은 정규장(09:00~15:30)에만 내되, 순매수 스냅샷은 프리마켓~애프터마켓(08:00~20:00) 내내 적재한다
    // — 세션 표의 프리·애프터 구간을 스냅샷 경계 diff로 만들려면 정규장 밖 누적도 필요하기 때문.
    private val signalStart: LocalTime = LocalTime.parse(sessionStart)
    private val signalEnd: LocalTime = LocalTime.parse(sessionEnd)

    @Scheduled(fixedDelayString = "\${leading-stock.market-signal.poll-interval-millis:30000}")
    fun onSchedule() {
        if (marketStatusService.getStatus().isHoliday) return
        val now = timeProvider.now().toLocalTime()
        if (now < SNAPSHOT_START || now > SNAPSHOT_END) return
        runCatching { detect() }.onFailure { log.warn(it) { "시장 시그널 적재 실패" } }
    }

    private fun detect() {
        val today = timeProvider.today()
        if (tradeDate.getAndSet(today) != today) {
            states.clear() // 일자 전환 — 직전 단계 폐기
            flowStates.clear()
        }
        // 재시작으로 메모리가 비었으면 그날 흐름 전환 정점을 DB 스냅샷에서 복원
        if (flowStates.isEmpty()) {
            flowStates.putAll(marketSignalEventService.loadFlowStates(today))
        }

        val now = timeProvider.now()
        // 정규장 밖(프리·애프터마켓)엔 순매수 스냅샷만 남기고 시그널은 내지 않는다.
        val emitSignals = now.toLocalTime() in signalStart..signalEnd
        val snapshots = mutableListOf<MarketInvestorSnapshot>()
        val recorded = Market.entries.flatMap { market ->
            val snapshot = sectorInvestorClient.fetchSectorNetBuy(market.mrktTp()) ?: return@flatMap emptyList()
            // 시그널 발생 시점 조회용으로 매 폴의 순매수를 그대로 적재(전이 여부와 무관).
            snapshots += MarketInvestorSnapshot(
                market = market, tradeDate = today, capturedAt = now,
                foreignEok = snapshot.foreignEok, institutionEok = snapshot.institutionEok,
                individualEok = snapshot.individualEok, otherCorpEok = snapshot.otherCorpEok,
                financialInvestmentEok = snapshot.financialInvestmentEok, trustEok = snapshot.trustEok,
                pensionFundEok = snapshot.pensionFundEok, privateEquityEok = snapshot.privateEquityEok,
                insuranceEok = snapshot.insuranceEok, bankEok = snapshot.bankEok,
                otherFinanceEok = snapshot.otherFinanceEok,
                indexValue = snapshot.indexValue, changeRate = snapshot.changeRate,
            )
            if (!emitSignals) return@flatMap emptyList()
            val step = MarketSignalThresholds.stepEok(market)
            val buffer = MarketSignalThresholds.bufferEok(market)

            netBuyByInvestor(snapshot).flatMap { (investor, netEok) ->
                val key = "$market|$investor"

                // 1) 순매수 단계 — 그날 같은 조합은 한 번만(재시작·회복 재발화 방지).
                val (transition, nextLevel) = (states[key] ?: InvestorNetBuyState.INITIAL)
                    .advance(netEok, step, buffer)
                states[key] = nextLevel
                val levelEvent = transition
                    ?.takeUnless {
                        marketSignalEventService.alreadyFiredNetBuyLevel(today, market, investor, it.side, it.level)
                    }
                    ?.let {
                        MarketSignalEvent.netBuyLevel(
                            occurredAt = now, tradeDate = today, market = market, investor = investor,
                            side = it.side, level = it.level, netAmountEok = netEok,
                            indexValue = snapshot.indexValue, changeRate = snapshot.changeRate,
                        )
                    }

                // 2) 흐름 전환 — 정점에서 임계 이상 되돌리면 방향 꺾임.
                val (turn, nextFlow) = (flowStates[key] ?: InvestorFlowState.INITIAL)
                    .advance(netEok, MarketSignalThresholds.reversalEok(market))
                flowStates[key] = nextFlow
                // 정점을 DB 스냅샷에 보존 — 재시작 시 복원해 전환을 놓치지 않게
                marketSignalEventService.saveFlowState(today, market, investor, nextFlow)
                val turnEvent = turn?.let {
                    MarketSignalEvent.netFlowTurn(
                        occurredAt = now, tradeDate = today, market = market, investor = investor,
                        side = it.to, extremeAmountEok = it.extremeEok, netAmountEok = netEok,
                        indexValue = snapshot.indexValue, changeRate = snapshot.changeRate,
                    )
                }

                listOfNotNull(levelEvent, turnEvent)
            }
        }

        marketSignalEventService.recordInvestorSnapshots(snapshots)
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

    companion object {
        private val SNAPSHOT_START: LocalTime = LocalTime.of(8, 0)  // NXT 프리마켓 개장
        private val SNAPSHOT_END: LocalTime = LocalTime.of(20, 0)    // NXT 애프터마켓 마감
    }
}
