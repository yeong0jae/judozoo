package at.backend.trading.application.runner

import at.backend.trading.domain.signal.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * 트레이딩 사이클 1건의 백그라운드 실행 단위.
 *
 * - 명령별로 1개 코루틴이 생성되어 매수 회차 → HOLDING → 매도 시그널 → 종료까지 진행한다.
 * - 외부에서는 [start] / [submitSignal] / [cancel] 만 호출한다. 내부 상태 전이 / 시그널 처리는
 *   [Mutex]로 직렬화되어 동시 접근 시에도 race가 발생하지 않는다.
 *
 * NOTE: 실제 매수/매도/종료 흐름은 후속 단계(4c-2 ~ 4c-5)에서 [run] 안에 채운다.
 */
class TradingCycleRunner(
    val cycleId: Long,
    private val applicationScope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val signals: Channel<Signal> = Channel(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var job: Job? = null

    fun start() {
        check(job == null) { "이미 실행 중인 사이클입니다: cycleId=$cycleId" }
        job = applicationScope.launch {
            log.info("TradingCycleRunner 시작 cycleId={}", cycleId)
            try {
                run()
            } finally {
                signals.close()
                log.info("TradingCycleRunner 종료 cycleId={}", cycleId)
            }
        }
    }

    suspend fun submitSignal(signal: Signal) {
        signals.send(signal)
    }

    suspend fun cancel() {
        job?.cancelAndJoin()
    }

    /**
     * 사이클 본 흐름. 4c-2에서 매수 회차, 4c-4에서 매도 시그널 처리를 채운다.
     * 현재는 시그널 채널만 비우며 외부 cancel을 기다린다 (골조).
     */
    private suspend fun run() {
        for (signal in signals) {
            mutex.withLock {
                // TODO 4c-4: 시그널 처리 — cycle.detectSignals와 결합 후 OrderExecutor.executeSell 호출
                log.debug("시그널 수신 (미처리, 골조 단계) cycleId={}, signal={}", cycleId, signal::class.simpleName)
            }
        }
    }
}
