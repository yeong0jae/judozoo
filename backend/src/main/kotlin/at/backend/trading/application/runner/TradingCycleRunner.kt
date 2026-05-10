package at.backend.trading.application.runner

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.market.application.BarPoller
import at.backend.market.application.MarketDataStream
import at.backend.market.domain.Bar
import at.backend.market.domain.PriceTick
import at.backend.trading.application.OrderService
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.event.*
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * 트레이딩 사이클 1건의 백그라운드 실행 단위.
 *
 * - 명령별로 1개 코루틴이 매수 회차 → HOLDING → 매도 시그널 → 종료까지 진행한다.
 * - 외부에서는 [start] / [submitSignal] / [cancel] 만 호출한다.
 * - 도메인 상태 전이는 [TradingCycle] 메서드(`startBuying` / `incrementBuyAttempt` / `transitionToHolding`
 *   / `armBreakeven` / `armTrendBreak` / `markTpStageFired` / `requestCancel` / `close`)로 위임 — runner는 흐름 제어만.
 */
class TradingCycleRunner(
    private val cycle: TradingCycle,
    private val applicationScope: CoroutineScope,
    private val orderService: OrderService,
    private val cycleRepository: TradingCycleJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
    private val marketDataStream: MarketDataStream,
    private val barPoller: BarPoller,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
    private val sellCostRate: Double,
    private val buyIntervalUnit: Duration = 1.minutes,
    private val holdingPollIntervalMillis: Long = 50,
) {

    val cycleId: Long = cycle.id
    val stockCode: String = cycle.stockCode

    private val log = LoggerFactory.getLogger(javaClass)
    private val signals: Channel<Signal> = Channel(Channel.UNLIMITED)
    private var job: Job? = null
    private var buyJob: Job? = null

    private var pendingCloseReason: CloseReason? = null
    private var prevBar: Bar? = null
    private var currentBar: Bar? = null

    fun start() {
        check(job == null) { "이미 실행 중인 사이클입니다: cycleId=$cycleId" }

        job = applicationScope.launch {
            log.info("TradingCycleRunner 시작 cycleId={}", cycleId)
            try {
                // BUYING 단계부터 tick/signal 수집 — 중도 익절·BUYING 손절 평가용
                val tickJob = launch {
                    marketDataStream.priceTicks
                        .filter { it.stockCode == cycle.stockCode }
                        .collect { tick -> processTick(tick) }
                }
                val signalJob = launch {
                    for (signal in signals) processExternalSignal(signal)
                }

                try {
                    val buy = launch { runBuySequence() }
                    buyJob = buy
                    buy.join()
                    buyJob = null

                    if (cycle.status == TradingCycleStatus.HOLDING || cycle.status == TradingCycleStatus.LIQUIDATING) {
                        handleAfterBuy()
                    }
                } finally {
                    tickJob.cancel()
                    signalJob.cancel()
                }
            } finally {
                signals.close()
                log.info("TradingCycleRunner 종료 cycleId={}, status={}", cycleId, cycle.status)
            }
        }
    }

    private suspend fun runBuySequence() {
        try {
            cycle.startBuying()
            cycleRepository.save(cycle)
            publishStateChanged(TradingCycleStatus.BUYING)
            orderService.placeOrder(cycle, attempt = 1)

            repeat(TradingCycle.MAX_BUY_ATTEMPT - 1) { i ->
                delay(buyIntervalUnit * cycle.buyIntervalMin)
                cycle.incrementBuyAttempt()
                cycleRepository.save(cycle)
                orderService.placeOrder(cycle, attempt = i + 2)
            }
        } finally {
            finalizeBuySequence()
        }
    }

    suspend fun submitSignal(signal: Signal) {
        signals.send(signal)
    }

    fun trySubmitSignal(signal: Signal): Boolean = signals.trySend(signal).isSuccess

    suspend fun cancel() {
        job?.cancelAndJoin()
    }

    suspend fun awaitCompletion() {
        job?.join()
    }

    /**
     * 외부(orchestrator) 취소 요청.
     * - in-memory cycle을 LIQUIDATING으로 전이시켜 finalize 분기를 CANCELLED로 유도
     * - 매수 코루틴 즉시 취소 → 다음 회차 미발사, race 차단
     * - HOLDING 전이된 경우를 위해 Signal.Cancel을 채널에 push (handleAfterBuy가 청산 처리)
     */
    suspend fun requestCancellation() {
        if (cycle.status != TradingCycleStatus.CLOSED && cycle.status != TradingCycleStatus.LIQUIDATING) {
            cycle.requestCancel()
            cycleRepository.save(cycle)
            publishStateChanged(TradingCycleStatus.LIQUIDATING)
        }
        buyJob?.cancelAndJoin()
        signals.trySend(Signal.Cancel)
    }

    private fun finalizeBuySequence() {
        val totalFilled = orderRepository.findByCycleId(cycleId)
            .filter { it.side == OrderSide.BUY }
            .sumOf { it.filledQty }

        when {
            totalFilled == 0 && cycle.status == TradingCycleStatus.LIQUIDATING -> {
                cycle.close(CloseReason.CANCELLED, timeProvider.now())
                cycleRepository.save(cycle)
                publishStateChanged(TradingCycleStatus.CLOSED, CloseReason.CANCELLED)
                publishCycleClosed(CloseReason.CANCELLED)
            }

            totalFilled == 0 && cycle.status == TradingCycleStatus.BUYING -> {
                cycle.close(CloseReason.NO_FILL, timeProvider.now())
                cycleRepository.save(cycle)
                publishStateChanged(TradingCycleStatus.CLOSED, CloseReason.NO_FILL)
                publishCycleClosed(CloseReason.NO_FILL)
            }

            cycle.status == TradingCycleStatus.BUYING -> {
                cycle.transitionToHolding()
                cycleRepository.save(cycle)
                publishStateChanged(TradingCycleStatus.HOLDING)
            }
            // status == LIQUIDATING && totalFilled > 0 → handleAfterBuy가 보유분을 청산하고 close
        }
        log.info("매수 시퀀스 종료 cycleId={}, totalFilled={}, status={}", cycleId, totalFilled, cycle.status)
    }

    private suspend fun handleAfterBuy() = coroutineScope {
        // tick/signal은 outer start()에서 BUYING 단계부터 이미 수집 중. 여기선 bar 수집과 종료 폴링만.
        val barJob = launch {
            barPoller.bars
                .filter { it.stockCode == cycle.stockCode }
                .collect { bar -> recordBar(bar) }
        }

        try {
            while (isActive) {
                delay(holdingPollIntervalMillis.milliseconds)
                if (checkAndCloseIfDone()) break
            }
        } finally {
            barJob.cancel()
        }
    }

    private fun recordBar(bar: Bar) {
        prevBar = currentBar
        currentBar = bar
    }

    private suspend fun processTick(tick: PriceTick) {
        when (cycle.status) {
            TradingCycleStatus.BUYING -> processTickDuringBuying(tick)
            TradingCycleStatus.HOLDING -> processTickDuringHolding(tick)
            else -> Unit
        }
    }

    /**
     * BUYING 중 tick 처리 — MidwayTakeProfit / StopLoss만 평가.
     * - MidwayTakeProfit: buyJob 취소 → finalizeBuySequence가 HOLDING 전이 → handleAfterBuy 첫 tick에서 TpStage 평가
     * - StopLoss: LIQUIDATING 전이 + 보유분 즉시 매도
     * 같은 시그널이 다음 tick에서 재발동하지 않도록 buyJob 취소 여부로 가드.
     */
    private suspend fun processTickDuringBuying(tick: PriceTick) {
        val currentBuyJob = buyJob ?: return
        if (currentBuyJob.isCancelled) return

        val state = computeHoldingState() ?: return
        publishPriceUpdated(tick.price, state.buyPrice, state.holdingQty)

        val signal = cycle.detectSignals(tick, state.holdingQty, state.buyPrice).firstOrNull() ?: return
        when (signal) {
            is Signal.MidwayTakeProfit -> {
                log.info("중도 익절 발동 cycleId={}, price={}", cycleId, tick.price)
                publishSignalFired(signal)
                currentBuyJob.cancel()
            }

            is Signal.StopLoss -> {
                log.info("BUYING 손절 발동 cycleId={}, price={}", cycleId, tick.price)
                publishSignalFired(signal)
                pendingCloseReason = signal.toCloseReason()
                cycle.requestCancel()
                cycleRepository.save(cycle)
                publishStateChanged(TradingCycleStatus.LIQUIDATING)
                currentBuyJob.cancel()
                if (state.holdingQty > 0) {
                    orderService.placeSell(cycle, signal, state.holdingQty, state.buyPrice, tick.price, currentBar)
                }
            }

            else -> Unit
        }
    }

    private suspend fun processTickDuringHolding(tick: PriceTick) {
        val state = computeHoldingState() ?: return
        publishPriceUpdated(tick.price, state.buyPrice, state.holdingQty)

        updateArming(tick.price, state.buyPrice)

        val signal = detectFirstSignal(tick, state.holdingQty, state.buyPrice) ?: return
        executeSignalSell(signal, state.holdingQty, state.buyPrice, tick.price, currentBar)
    }

    private fun detectFirstSignal(tick: PriceTick, holdingQty: Int, buyPrice: Int): Signal? {
        val current = currentBar
        val prev = prevBar
        val signals = if (current != null && prev != null) {
            cycle.detectSignals(tick, current, prev, holdingQty, buyPrice)
        } else {
            cycle.detectSignals(tick, holdingQty, buyPrice)
        }
        return signals.firstOrNull()
    }

    private suspend fun processExternalSignal(signal: Signal) {
        if (cycle.status == TradingCycleStatus.CLOSED) return
        val state = computeHoldingState()
        if (state == null) {
            if (signal == Signal.Cancel) {
                cycle.close(CloseReason.CANCELLED, timeProvider.now())
                cycleRepository.save(cycle)
                publishStateChanged(TradingCycleStatus.CLOSED, CloseReason.CANCELLED)
                publishCycleClosed(CloseReason.CANCELLED)
            }
            return
        }
        executeSignalSell(signal, state.holdingQty, state.buyPrice, state.buyPrice, currentBar)
    }

    private suspend fun executeSignalSell(
        signal: Signal,
        holdingQty: Int,
        buyPrice: Int,
        currentPrice: Int,
        currentBar: Bar?,
    ) {
        publishSignalFired(signal)
        when (signal) {
            is Signal.TpStage -> {
                cycle.markTpStageFired(signal.pct)
                cycleRepository.save(cycle)
                val (sellQty, _) = cycle.splitSellQty(holdingQty)
                if (sellQty > 0) {
                    orderService.placeSell(cycle, signal, sellQty, buyPrice, currentPrice, currentBar)
                }
            }

            else -> {
                pendingCloseReason = signal.toCloseReason()
                if (cycle.status == TradingCycleStatus.HOLDING) {
                    cycle.requestCancel()
                    cycleRepository.save(cycle)
                    publishStateChanged(TradingCycleStatus.LIQUIDATING)
                }
                if (holdingQty > 0) {
                    orderService.placeSell(cycle, signal, holdingQty, buyPrice, currentPrice, currentBar)
                }
            }
        }
    }

    private fun checkAndCloseIfDone(): Boolean {
        if (cycle.status == TradingCycleStatus.CLOSED) return true
        val state = computeHoldingState() ?: return false
        val inFlight = orderRepository.inFlightSellUnfilled(cycleId)
        val allTpFired = cycle.tpStagesFired and 0b111 == 0b111

        if (cycle.status == TradingCycleStatus.HOLDING && allTpFired && state.holdingQty == 0 && inFlight == 0) {
            cycle.requestCancel()
            cycleRepository.save(cycle)
            pendingCloseReason = CloseReason.TAKE_PROFIT
        }

        if (cycle.status == TradingCycleStatus.LIQUIDATING && state.holdingQty == 0 && inFlight == 0) {
            val reason = pendingCloseReason ?: CloseReason.UNCLOSED
            cycle.close(reason, timeProvider.now())
            cycleRepository.save(cycle)
            publishStateChanged(TradingCycleStatus.CLOSED, reason)
            publishCycleClosed(reason)
            return true
        }
        return false
    }

    private fun computeHoldingState(): HoldingState? {
        val orders = orderRepository.findByCycleId(cycleId)
        val buyOrders = orders.filter { it.side == OrderSide.BUY }
        val boughtQty = buyOrders.sumOf { it.filledQty }
        if (boughtQty == 0) return null

        val buyExecutions = buyOrders.flatMap { executionRepository.findByOrderId(it.id) }
        if (buyExecutions.isEmpty()) return null

        val sellOrders = orders.filter { it.side == OrderSide.SELL }
        val soldQty = sellOrders.sumOf { it.filledQty }
        val buyPrice = cycle.calculateBuyPrice(buyExecutions, sellCostRate)

        return HoldingState(holdingQty = boughtQty - soldQty, buyPrice = buyPrice)
    }

    private fun updateArming(price: Int, buyPrice: Int) {
        if (cycle.status != TradingCycleStatus.HOLDING) return
        if (!cycle.breakevenArmed && reachedBreakevenThreshold(price, buyPrice)) {
            cycle.armBreakeven()
            cycleRepository.save(cycle)
            publishSignalArmed("Breakeven")
        }
        if (!cycle.trendBreakArmed && price >= (buyPrice * (1.0 + TREND_BREAK_ARM_PCT)).toInt()) {
            cycle.armTrendBreak()
            cycleRepository.save(cycle)
            publishSignalArmed("TrendBreak")
        }
    }

    private fun reachedBreakevenThreshold(price: Int, buyPrice: Int): Boolean {
        val threshold = buyPrice * (1.0 + cycle.breakevenThresholdPct.toDouble() / 100.0)
        return price >= threshold.toInt()
    }

    private fun Signal.toCloseReason(): CloseReason = when (this) {
        Signal.StopLoss -> CloseReason.STOP_LOSS
        Signal.Breakeven -> CloseReason.BREAKEVEN
        Signal.TrendBreak -> CloseReason.TREND_BREAK
        Signal.MarketClose, Signal.LimitUp -> CloseReason.MARKET_CLOSE
        Signal.Cancel -> CloseReason.CANCELLED
        Signal.MidwayTakeProfit -> CloseReason.TAKE_PROFIT
        is Signal.TpStage -> CloseReason.TAKE_PROFIT
    }

    private fun publishCycleClosed(reason: CloseReason) {
        val instant = timeProvider.now().toInstantKst()
        eventPublisher.publishEvent(
            TradingCycleClosed(cycleId = cycleId, closeReason = reason.name, ts = instant)
        )
    }

    private fun publishStateChanged(status: TradingCycleStatus, closeReason: CloseReason? = null) {
        eventPublisher.publishEvent(
            CycleStateChanged(
                cycleId = cycleId,
                status = status.name,
                closeReason = closeReason?.name,
                ts = timeProvider.now().toInstantKst(),
            )
        )
    }

    private fun publishPriceUpdated(currentPrice: Int, buyPrice: Int, holdingQty: Int) {
        val profitRate = if (buyPrice > 0) {
            BigDecimal((currentPrice - buyPrice).toDouble() / buyPrice * 100.0)
                .setScale(3, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
        val profitAmount = (currentPrice - buyPrice).toLong() * holdingQty
        eventPublisher.publishEvent(
            PriceUpdated(
                cycleId = cycleId,
                currentPrice = currentPrice,
                profitRate = profitRate,
                profitAmount = profitAmount,
                ts = timeProvider.now().toInstantKst(),
            )
        )
    }

    private fun publishSignalArmed(signalType: String) {
        eventPublisher.publishEvent(
            SignalArmed(
                cycleId = cycleId,
                signalType = signalType,
                ts = timeProvider.now().toInstantKst(),
            )
        )
    }

    private fun publishSignalFired(signal: Signal) {
        val (signalType, stage) = when (signal) {
            is Signal.TpStage -> "TpStage" to signal.pct
            else -> (signal::class.simpleName ?: "Signal") to null
        }
        eventPublisher.publishEvent(
            SignalFired(
                cycleId = cycleId,
                signalType = signalType,
                stage = stage,
                ts = timeProvider.now().toInstantKst(),
            )
        )
    }

    private data class HoldingState(val holdingQty: Int, val buyPrice: Int)

    companion object {
        private const val TREND_BREAK_ARM_PCT = 0.05
    }
}
