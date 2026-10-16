package judozoo.leadingstock.application

import judozoo.leadingstock.domain.InvestorFlowState
import judozoo.leadingstock.domain.InvestorNetBuyState
import judozoo.leadingstock.domain.InvestorType
import judozoo.leadingstock.domain.MarketFlowState
import judozoo.leadingstock.domain.MarketFlowStateRepository
import judozoo.leadingstock.domain.MarketSignalEvent
import judozoo.leadingstock.domain.MarketSignalEventRepository
import judozoo.leadingstock.domain.MarketSignalScale
import judozoo.leadingstock.domain.MarketSignalType
import judozoo.library.KST
import judozoo.market.application.MarketInvestors
import judozoo.platform.kiwoom.KiwoomSectorInvestor
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 시장 수급 — 투자자 순매수 스냅샷을 남기고, 단계 돌파·흐름 전환 시그널을 적재한다(1분마다).
 *
 * **정규장 밖(프리·애프터)엔 스냅샷만** 남기고 시그널은 내지 않는다. 시황의 세션 표가 프리·애프터 구간을
 * 스냅샷 경계 차이로 만들어서 정규장 밖 누적도 필요하다.
 *
 * 직전 상태는 메모리에 들고 일자가 바뀌면 버린다. 흐름 전환 정점만 DB(`market_flow_state`)에 남겨 재시작에도 복원한다.
 */
@Component
class MarketFlowDetector(
    private val sector: KiwoomSectorInvestor,
    private val investors: MarketInvestors,
    private val events: MarketSignalEventRepository,
    private val flowStates: MarketFlowStateRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var day: LocalDate? = null
    private val levels = HashMap<Pair<Market, InvestorType>, InvestorNetBuyState>()
    private val flows = HashMap<Pair<Market, InvestorType>, InvestorFlowState>()

    @Synchronized
    fun detect() {
        val at = LocalDateTime.now(clock.withZone(KST))
        val today = at.toLocalDate()
        if (day != today) {
            levels.clear()
            flows.clear()
            day = today
        }
        // 재시작으로 메모리가 비었으면 그날 흐름 전환 정점을 DB에서 복원한다
        if (flows.isEmpty()) flowStates.findByTradeDate(today).forEach { flows[it.market to it.investor] = it.state() }
        val emitSignals = !at.toLocalTime().isBefore(SIGNAL_START) && !at.toLocalTime().isAfter(SIGNAL_END)

        val recorded = mutableListOf<MarketSignalEvent>()
        for (market in Market.entries) {
            val net = sector.fetchSectorNetBuy(MARKET_TYPES.getValue(market)) ?: continue
            investors.record(market, today, at, net)
            if (!emitSignals) continue

            val scale = MarketSignalScale.of(market)
            for ((investor, netEok) in listOf(InvestorType.FOREIGN to net.foreignEok, InvestorType.INSTITUTION to net.institutionEok, InvestorType.INDIVIDUAL to net.individualEok)) {
                val key = market to investor

                // 1) 순매수 단계 — 그날 같은 조합은 한 번만(재시작·회복 재발화 방지)
                val (transition, level) = levels.getOrDefault(key, InvestorNetBuyState()).advance(netEok, scale.stepEok, scale.bufferEok)
                levels[key] = level
                if (transition != null &&
                    !events.existsByTradeDateAndKindAndMarketAndInvestorAndSideAndLevel(today, MarketSignalType.NET_BUY_LEVEL.name, market, investor, transition.side, transition.level)
                ) {
                    recorded += MarketSignalEvent.netBuyLevel(at, today, market, investor, transition, netEok, net.indexValue, net.changeRate)
                }

                // 2) 흐름 전환 — 정점에서 임계 이상 되돌리면 방향 꺾임
                val (turn, flow) = flows.getOrDefault(key, InvestorFlowState()).advance(netEok, scale.reversalEok)
                flows[key] = flow
                saveFlow(market, investor, flow, today, at)
                if (turn != null) recorded += MarketSignalEvent.flowTurn(at, today, market, investor, turn, netEok, net.indexValue, net.changeRate)
            }
        }
        if (recorded.isEmpty()) return
        events.saveAll(recorded)
        log.info("시장 시그널 {}건 적재", recorded.size)
    }

    /** 정점을 DB에 보존한다 — 재시작 때 복원해 전환을 놓치지 않게. 아직 방향이 없으면(추적 전) 남기지 않는다. */
    private fun saveFlow(market: Market, investor: InvestorType, state: InvestorFlowState, today: LocalDate, at: LocalDateTime) {
        val side = state.side ?: return
        val row = flowStates.findByMarketAndInvestor(market, investor)?.apply { track(state, today, at) }
            ?: MarketFlowState(market, investor, side, state.extremeEok, today, at)
        flowStates.save(row)
    }

    companion object {
        /** 키움 업종 시장 구분 */
        private val MARKET_TYPES = mapOf(Market.KOSPI to "0", Market.KOSDAQ to "1")

        /** 시그널을 내는 정규장 */
        val SIGNAL_START: LocalTime = LocalTime.of(9, 0)
        val SIGNAL_END: LocalTime = LocalTime.of(15, 30)
    }
}
