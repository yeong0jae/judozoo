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

    internal fun forgetStates() = synchronized(this) { states.clear() }

    companion object {
        private val COOLDOWN = Duration.ofMinutes(3)
    }
}
