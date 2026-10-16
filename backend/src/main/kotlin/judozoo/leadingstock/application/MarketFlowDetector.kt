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
        if (flows.isEmpty()) flowStates.findByTradeDate(today).forEach { flows[it.market to it.investor] = it.state() }
        val emitSignals = !at.toLocalTime().isBefore(SIGNAL_START) && !at.toLocalTime().isAfter(SIGNAL_END)

        val recorded = mutableListOf<MarketSignalEvent>()
        for (market in Market.entries) {
            val net = sector.fetchSectorNetBuy(market) ?: continue
            investors.record(market, today, at, net)
            if (!emitSignals) continue

            val scale = MarketSignalScale.of(market)
            for ((investor, netEok) in listOf(InvestorType.FOREIGN to net.foreignEok, InvestorType.INSTITUTION to net.institutionEok, InvestorType.INDIVIDUAL to net.individualEok)) {
                val key = market to investor

                val (transition, level) = levels.getOrDefault(key, InvestorNetBuyState()).advance(netEok, scale.stepEok, scale.bufferEok)
                levels[key] = level
                if (transition != null &&
                    !events.existsByTradeDateAndKindAndMarketAndInvestorAndSideAndLevel(today, MarketSignalType.NET_BUY_LEVEL, market, investor, transition.side, transition.level)
                ) {
                    recorded += MarketSignalEvent.netBuyLevel(at, today, market, investor, transition, netEok, net.indexValue, net.changeRate)
                }

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

    private fun saveFlow(market: Market, investor: InvestorType, state: InvestorFlowState, today: LocalDate, at: LocalDateTime) {
        val side = state.side ?: return
        val row = flowStates.findByMarketAndInvestor(market, investor)?.apply { track(state, today, at) }
            ?: MarketFlowState(market, investor, side, state.extremeEok, today, at)
        flowStates.save(row)
    }

    companion object {
        val SIGNAL_START: LocalTime = LocalTime.of(9, 0)
        val SIGNAL_END: LocalTime = LocalTime.of(15, 30)
    }
}
