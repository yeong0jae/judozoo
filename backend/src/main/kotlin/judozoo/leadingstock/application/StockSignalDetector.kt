package judozoo.leadingstock.application

import judozoo.leadingstock.domain.SignalEvent
import judozoo.leadingstock.domain.SignalEventRepository
import judozoo.leadingstock.domain.SignalEventType
import judozoo.leadingstock.domain.SignalState
import judozoo.library.KST
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 종목 시그널 — 감시 풀의 거래대금 스파이크 전이를 적재한다(10초마다).
 *
 * 직전 상태는 메모리에 들고 일자가 바뀌면 버린다 — 어제 상태를 들고 있으면 오늘 첫 전이를 놓친다.
 * 재시작하면 상태가 비지만, 같은 종목·종류가 [COOLDOWN] 안에 또 뜨면 건너뛴다.
 */
@Component
class StockSignalDetector(
    private val service: LeadingStockService,
    private val events: SignalEventRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var day: LocalDate? = null
    private val states = HashMap<String, SignalState>()
    private val lastFired = HashMap<Pair<String, SignalEventType>, LocalDateTime>()

    @Synchronized
    fun detect() {
        val at = LocalDateTime.now(clock.withZone(KST))
        val today = at.toLocalDate()
        if (day != today) {
            states.clear()
            lastFired.clear()
            day = today
        }

        val recorded = service.signalReadings().flatMap { (stock, spike) ->
            val (fired, next) = states.getOrDefault(stock.stockCode, SignalState()).advance(spike?.ratio)
            states[stock.stockCode] = next
            // 같은 종목·종류가 쿨다운 안에 또 뜨면 중복으로 보고 건너뛴다 (프리마켓 출렁임 대응)
            fired.filter { type ->
                val key = stock.stockCode to type
                val last = lastFired[key]
                (last == null || !last.isAfter(at.minus(COOLDOWN))).also { if (it) lastFired[key] = at }
            }.map { SignalEvent.volumeSpike(at, today, stock, spike!!) }
        }
        if (recorded.isEmpty()) return
        events.saveAll(recorded)
        log.info("시그널 전이 {}건 적재", recorded.size)
    }

    /** 테스트가 재시작(상태 유실)을 흉내 낸다. */
    internal fun forgetStates() = synchronized(this) { states.clear() }

    companion object {
        /** 같은 종목·종류 재적재 쿨다운 */
        private val COOLDOWN = Duration.ofMinutes(3)
    }
}
