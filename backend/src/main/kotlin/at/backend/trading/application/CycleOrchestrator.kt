package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.market.application.BarPoller
import at.backend.market.application.MarketDataStream
import at.backend.trading.TradingProperties
import at.backend.trading.application.runner.TradingCycleRunner
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@Component
class CycleOrchestrator(
    private val applicationScope: CoroutineScope,
    private val orderService: OrderService,
    private val cycleRepository: TradingCycleJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
    private val marketDataStream: MarketDataStream,
    private val barPoller: BarPoller,
    private val timeProvider: TimeProvider,
    private val tradingProperties: TradingProperties,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    @Value("\${trading.cycle.buy-interval-unit-millis:60000}") private val buyIntervalUnitMillis: Long,
    @Value("\${trading.cycle.holding-poll-interval-millis:50}") private val holdingPollIntervalMillis: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val activeRunners = ConcurrentHashMap<Long, TradingCycleRunner>()

    fun start(cycle: TradingCycle) {
        if (activeRunners.containsKey(cycle.id)) {
            log.warn("이미 실행 중인 사이클 — start 무시 cycleId={}", cycle.id)
            return
        }

        marketDataStream.subscribe(cycle.stockCode)
        barPoller.subscribe(cycle.stockCode)

        val tradingCycleRunner = newTradingCycleRunner(cycle)
        activeRunners[cycle.id] = tradingCycleRunner
        tradingCycleRunner.start()
        applicationScope.launch {
            runCatching { tradingCycleRunner.awaitCompletion() }
                .onFailure { log.warn("사이클 종료 대기 중 오류 cycleId={}", cycle.id, it) }
            cleanup(cycle.id)
        }
        log.info("CycleOrchestrator 시작 cycleId={}, stockCode={}", cycle.id, cycle.stockCode)
    }

    fun cancel(cycleId: Long) {
        val runner = activeRunners[cycleId]
        if (runner == null) {
            log.warn("취소 대상 사이클이 활성 상태가 아님 cycleId={}", cycleId)
            return
        }
        orderService.cancelInFlightBuys(cycleId)
        applicationScope.launch {
            runCatching { runner.requestCancellation() }
                .onFailure { log.warn("사이클 취소 처리 실패 cycleId={}", cycleId, it) }
        }
    }

    fun broadcastMarketClose() {
        val snapshot = activeRunners.values.toList()
        log.info("MarketClose 일제 라우팅 — 대상 cycle 수={}", snapshot.size)
        for (runner in snapshot) {
            runner.trySubmitSignal(Signal.MarketClose)
        }
    }

    fun activeCycleIds(): Set<Long> = activeRunners.keys.toSet()

    private fun cleanup(cycleId: Long) {
        val removed = activeRunners.remove(cycleId) ?: return
        marketDataStream.unsubscribe(removed.stockCode)
        barPoller.unsubscribe(removed.stockCode)
        log.info("CycleOrchestrator 정리 cycleId={}, stockCode={}", cycleId, removed.stockCode)
    }

    private fun newTradingCycleRunner(cycle: TradingCycle) = TradingCycleRunner(
        cycle = cycle,
        applicationScope = applicationScope,
        orderService = orderService,
        cycleRepository = cycleRepository,
        orderRepository = orderRepository,
        executionRepository = executionRepository,
        marketDataStream = marketDataStream,
        barPoller = barPoller,
        timeProvider = timeProvider,
        eventPublisher = eventPublisher,
        sellCostRate = tradingProperties.sellCostRate.toDouble(),
        buyIntervalUnit = buyIntervalUnitMillis.milliseconds,
        holdingPollIntervalMillis = holdingPollIntervalMillis,
    )
}
