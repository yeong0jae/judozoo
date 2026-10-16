package judozoo.leadingstock.application

import judozoo.leadingstock.application.StockMinuteArchive.Companion.baseCode
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.library.KST
import judozoo.library.SingleFlight
import judozoo.platform.toss.TossCandles
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeMap

@Component
class TodayMinutes(private val toss: TossCandles, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal var maxStocks = MAX_STOCKS

    private val lock = Any()
    private var day: LocalDate? = null
    private val bars = HashMap<String, TreeMap<LocalDateTime, MinuteCandle>>()
    private val syncedAt = HashMap<String, LocalDateTime>()
    private val readAt = HashMap<String, LocalDateTime>()

    private val flight = SingleFlight<String, Unit>()

    fun sync(stockCodes: List<String>): Int {
        val keys = stockCodes.map(::baseCode)
        val today = now().toLocalDate()
        synchronized(lock) {
            rollOver(today)
            evict(keep = keys.toSet())
        }
        return keys.count { key ->
            try {
                syncOne(key, today)
                false
            } catch (e: Exception) {
                log.warn("당일 분봉 갱신 실패 stk_cd={}", key, e)
                true
            }
        }
    }

    data class Settled(val bars: Map<String, List<MinuteCandle>>, val failures: Int)

    fun settle(stockCodes: List<String>): Settled {
        val started = now()
        val keys = stockCodes.map(::baseCode).distinct()
        val failures = sync(keys)
        return synchronized(lock) {
            if (day != started.toLocalDate()) return Settled(emptyMap(), failures)
            Settled(keys.filter { it in bars && isSyncedSince(it, started) }.associateWith { bars.getValue(it).values.toList() }, failures)
        }
    }

    fun ensure(stockCode: String, freshSince: LocalDateTime): List<MinuteCandle> {
        val key = baseCode(stockCode)
        val at = now()
        synchronized(lock) {
            rollOver(at.toLocalDate())
            readAt[key] = at
            if (key in bars && isSyncedSince(key, freshSince)) return bars.getValue(key).values.toList()
            evict(keep = setOf(key))
        }
        try {
            flight.run(key) { syncIfStale(key, at.toLocalDate(), freshSince) }
        } catch (e: Exception) {
            log.error("당일 분봉 조회 실패 stk_cd={}", key, e)
        }
        return synchronized(lock) { bars[key]?.values?.toList().orEmpty() }
    }

    fun syncedSince(stockCode: String, since: LocalDateTime): Boolean = synchronized(lock) { isSyncedSince(baseCode(stockCode), since) }

    private fun isSyncedSince(key: String, since: LocalDateTime) = syncedAt[key]?.let { !it.isBefore(since) } ?: false

    private fun syncIfStale(key: String, today: LocalDate, freshSince: LocalDateTime) {
        if (synchronized(lock) { isSyncedSince(key, freshSince) }) return
        syncOne(key, today)
    }

    private fun syncOne(key: String, today: LocalDate) {
        val have = synchronized(lock) { bars[key]?.lastKey() }
        val fresh = toss.fetchTodayMinuteCandles(key, have?.minus(REWRITE))
        synchronized(lock) {
            if (day != today) return
            val merged = TreeMap(bars[key].orEmpty())
            fresh.forEach { merged[it.dateTime] = it }
            bars[key] = merged
            syncedAt[key] = now()
        }
    }

    private fun evict(keep: Set<String>) {
        val overflow = (bars.keys + keep).size - maxStocks
        if (overflow <= 0) return
        bars.keys.filter { it !in keep }.sortedBy { readAt[it] ?: LocalDateTime.MIN }.take(overflow).forEach {
            bars.remove(it)
            syncedAt.remove(it)
            readAt.remove(it)
        }
    }

    private fun rollOver(today: LocalDate) {
        if (day == today) return
        bars.clear()
        syncedAt.clear()
        readAt.clear()
        day = today
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    companion object {
        private val REWRITE = Duration.ofMinutes(2)

        private const val MAX_STOCKS = 100
    }
}
