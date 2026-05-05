package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.market.application.MarketDataStream
import at.backend.market.infrastructure.BarCache
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
    private val orderExecutor: OrderExecutor,
    private val cycleRepository: TradingCycleJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
    private val marketDataStream: MarketDataStream,
    private val barCache: BarCache,
    private val timeProvider: TimeProvider,
    private val tradingProperties: TradingProperties,
    @Value("\${trading.cycle.buy-interval-unit-millis:60000}") private val buyIntervalUnitMillis: Long,
    @Value("\${trading.cycle.holding-poll-interval-millis:50}") private val holdingPollIntervalMillis: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val activeRunners = ConcurrentHashMap<Long, ActiveRunner>()

    fun start(cycle: TradingCycle) {
        val existing = activeRunners[cycle.id]
        if (existing != null) {
            log.warn("이미 실행 중인 사이클 — start 무시 cycleId={}", cycle.id)
            return
        }

        marketDataStream.subscribe(cycle.stockCode)
        barCache.subscribe(cycle.stockCode)

        val runner = newRunner(cycle)
        activeRunners[cycle.id] = ActiveRunner(runner, cycle.stockCode)
        runner.start()
        applicationScope.launch {
            runCatching { runner.awaitCompletion() }
                .onFailure { log.warn("사이클 종료 대기 중 오류 cycleId={}", cycle.id, it) }
            cleanup(cycle.id)
        }
        log.info("CycleOrchestrator 시작 cycleId={}, stockCode={}", cycle.id, cycle.stockCode)
    }

    fun cancel(cycleId: Long) {
        val active = activeRunners[cycleId]
        if (active == null) {
            log.warn("취소 대상 사이클이 활성 상태가 아님 cycleId={}", cycleId)
            return
        }
        orderExecutor.cancelInFlightBuys(cycleId)
        active.runner.trySubmitSignal(Signal.Cancel)
    }

    fun activeCycleIds(): Set<Long> = activeRunners.keys.toSet()

    private fun cleanup(cycleId: Long) {
        val removed = activeRunners.remove(cycleId) ?: return
        marketDataStream.unsubscribe(removed.stockCode)
        barCache.unsubscribe(removed.stockCode)
        log.info("CycleOrchestrator 정리 cycleId={}, stockCode={}", cycleId, removed.stockCode)
    }

    private fun newRunner(cycle: TradingCycle): TradingCycleRunner = TradingCycleRunner(
        cycle = cycle,
        applicationScope = applicationScope,
        orderExecutor = orderExecutor,
        cycleRepository = cycleRepository,
        orderRepository = orderRepository,
        executionRepository = executionRepository,
        marketDataStream = marketDataStream,
        barCache = barCache,
        timeProvider = timeProvider,
        sellCostRate = tradingProperties.sellCostRate.toDouble(),
        buyIntervalUnit = buyIntervalUnitMillis.milliseconds,
        holdingPollIntervalMillis = holdingPollIntervalMillis,
    )

    private data class ActiveRunner(val runner: TradingCycleRunner, val stockCode: String)
}
