package at.backend.trading.application.runner

import at.backend.library.time.TimeProvider
import at.backend.trading.application.OrderExecutor
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 트레이딩 사이클 1건의 백그라운드 실행 단위.
 *
 * - 명령별로 1개 코루틴이 매수 회차 → HOLDING → 매도 시그널 → 종료까지 진행한다.
 * - 외부에서는 [start] / [submitSignal] / [cancel] 만 호출한다.
 * - 도메인 상태 전이는 [TradingCycle] 메서드(`startBuying` / `incrementBuyAttempt` / `transitionToHolding` / `close`)로 위임 — runner는 흐름 제어만.
 *
 * NOTE: 매도 시그널 처리(HOLDING → 종료)는 후속 단계(4c-4)에서 채운다. 현재는 매수 회차 + HOLDING 진입까지.
 */
class TradingCycleRunner(
    private val cycle: TradingCycle,
    private val applicationScope: CoroutineScope,
    private val orderExecutor: OrderExecutor,
    private val cycleRepository: TradingCycleJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val timeProvider: TimeProvider,
    private val buyIntervalUnit: Duration = 1.minutes,
) {

    val cycleId: Long = cycle.id

    private val log = LoggerFactory.getLogger(javaClass)
    private val signals: Channel<Signal> = Channel(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var job: Job? = null

    fun start() {
        check(job == null) { "이미 실행 중인 사이클입니다: cycleId=$cycleId" }
        job = applicationScope.launch {
            log.info("TradingCycleRunner 시작 cycleId={}", cycleId)
            try {
                runBuySequence()
                // TODO 4c-4: HOLDING 상태에서 매도 시그널 처리 루프
            } finally {
                signals.close()
                log.info("TradingCycleRunner 종료 cycleId={}, status={}", cycleId, cycle.status)
            }
        }
    }

    suspend fun submitSignal(signal: Signal) {
        signals.send(signal)
    }

    suspend fun cancel() {
        job?.cancelAndJoin()
    }

    private suspend fun runBuySequence() {
        mutex.withLock {
            cycle.startBuying()
            cycleRepository.save(cycle)
        }
        orderExecutor.executeBuyTry(cycle, attempt = 1)

        repeat(TradingCycle.MAX_BUY_ATTEMPT - 1) { i ->
            delay(buyIntervalUnit * cycle.buyIntervalMin)
            mutex.withLock {
                cycle.incrementBuyAttempt()
                cycleRepository.save(cycle)
            }
            orderExecutor.executeBuyTry(cycle, attempt = i + 2)
        }

        finalizeBuySequence()
    }

    private suspend fun finalizeBuySequence() {
        val totalFilled = orderRepository.findByCycleId(cycleId)
            .filter { it.side == "BUY" }
            .sumOf { it.filledQty }

        mutex.withLock {
            if (totalFilled == 0) {
                cycle.close(CloseReason.NO_FILL, timeProvider.now())
            } else {
                cycle.transitionToHolding()
            }
            cycleRepository.save(cycle)
        }
        log.info("매수 시퀀스 종료 cycleId={}, totalFilled={}, status={}", cycleId, totalFilled, cycle.status)
    }
}
