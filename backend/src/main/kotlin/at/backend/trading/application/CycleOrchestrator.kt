package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.market.application.BarPoller
import at.backend.market.application.PriceTickDataStream
import at.backend.trading.TradingProperties
import at.backend.trading.application.runner.TradingCycleRunner
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val priceTickDataStream: PriceTickDataStream,
    private val barPoller: BarPoller,
    private val timeProvider: TimeProvider,
    private val tradingProperties: TradingProperties,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    @Value("\${trading.cycle.buy-interval-unit-millis}") private val buyIntervalUnitMillis: Long,
    @Value("\${trading.cycle.holding-poll-interval-millis}") private val holdingPollIntervalMillis: Long,
) {

    private val log = KotlinLogging.logger {}
    private val activeRunners = ConcurrentHashMap<Long, TradingCycleRunner>()

    fun start(cycle: TradingCycle) {
        if (activeRunners.containsKey(cycle.id)) {
            log.warn { "이미 실행 중인 사이클 — start 무시 cycleId=${cycle.id}" }
            return
        }

        priceTickDataStream.subscribe(cycle.stockCode)
        barPoller.subscribe(cycle.stockCode)

        val tradingCycleRunner = newTradingCycleRunner(cycle)
        activeRunners[cycle.id] = tradingCycleRunner
        tradingCycleRunner.start()
        applicationScope.launch {
            runCatching { tradingCycleRunner.awaitCompletion() }
                .onFailure { log.warn(it) { "사이클 종료 대기 중 오류 cycleId=${cycle.id}" } }
            cleanup(cycle.id)
        }
        log.info { "CycleOrchestrator 시작 cycleId=${cycle.id}, stockCode=${cycle.stockCode}" }
    }

    fun cancel(cycleId: Long) {
        val runner = activeRunners[cycleId]
        if (runner == null) {
            log.warn { "취소 대상 사이클이 활성 상태가 아님 cycleId=$cycleId" }
            return
        }
        orderService.cancelInFlightBuys(cycleId)
        applicationScope.launch {
            runCatching { runner.requestCancellation() }
                .onFailure { log.warn(it) { "사이클 취소 처리 실패 cycleId=$cycleId" } }
        }
    }

    fun activeCycleIds(): Set<Long> = activeRunners.keys.toSet()

    private fun cleanup(cycleId: Long) {
        val removed = activeRunners.remove(cycleId) ?: return
        priceTickDataStream.unsubscribe(removed.stockCode)
        barPoller.unsubscribe(removed.stockCode)
        log.info { "CycleOrchestrator 정리 cycleId=$cycleId, stockCode=${removed.stockCode}" }
    }

    private fun newTradingCycleRunner(cycle: TradingCycle) = TradingCycleRunner(
        cycle = cycle,
        applicationScope = applicationScope,
        orderService = orderService,
        cycleRepository = cycleRepository,
        orderRepository = orderRepository,
        executionRepository = executionRepository,
        priceTickDataStream = priceTickDataStream,
        barPoller = barPoller,
        timeProvider = timeProvider,
        eventPublisher = eventPublisher,
        sellCostRate = tradingProperties.sellCostRate.toDouble(),
        buyIntervalUnit = buyIntervalUnitMillis.milliseconds,
        holdingPollIntervalMillis = holdingPollIntervalMillis,
    )
}
