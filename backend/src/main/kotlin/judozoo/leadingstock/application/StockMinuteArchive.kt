package judozoo.leadingstock.application

import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.StockMinuteCandleEntity
import judozoo.leadingstock.domain.StockMinuteCandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate

@Component
class StockMinuteArchive(
    private val repository: StockMinuteCandleRepository,
    private val transaction: TransactionTemplate,
    private val ticker: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Remembered(val storedAt: Long, val bars: List<MinuteCandle>)

    private val days = LinkedHashMap<Pair<String, LocalDate>, Remembered>()

    fun get(stockCode: String, day: LocalDate): List<MinuteCandle>? {
        val key = baseCode(stockCode) to day
        recall(key)?.let { return it }
        val bars = try {
            repository.findByStockCodeAndTradeDateOrderByMinuteAt(key.first, day).map { it.toCandle() }
        } catch (e: Exception) {
            log.warn("지난 날 분봉 DB 조회 실패 stk_cd={} day={}", key.first, day, e)
            return null
        }
        if (bars.isEmpty()) return null
        remember(key, bars)
        return bars
    }

    fun put(stockCode: String, day: LocalDate, bars: List<MinuteCandle>) {
        if (bars.isEmpty()) return
        val code = baseCode(stockCode)
        val ordered = bars.sortedBy { it.dateTime }
        remember(code to day, ordered)
        try {
            transaction.executeWithoutResult {
                repository.deleteDay(code, day)
                repository.saveAll(ordered.map { StockMinuteCandleEntity.of(code, day, it) })
            }
        } catch (e: Exception) {
            log.warn("지난 날 분봉 DB 저장 실패 stk_cd={} day={}", code, day, e)
        }
    }

    fun purge(before: LocalDate): Int {
        synchronized(days) { days.keys.removeIf { it.second < before } }
        return transaction.execute { repository.deleteBefore(before) } ?: 0
    }

    fun putPage(stockCode: String, page: List<MinuteCandle>) {
        val byDay = page.groupBy { it.dateTime.toLocalDate() }
        if (byDay.isEmpty()) return
        val cut = byDay.keys.min()
        byDay.filterKeys { it != cut }.forEach { (day, bars) -> put(stockCode, day, bars) }
    }

    private fun recall(key: Pair<String, LocalDate>): List<MinuteCandle>? = synchronized(days) {
        val entry = days[key] ?: return null
        if (ticker() - entry.storedAt > TTL.toNanos()) {
            days.remove(key)
            return null
        }
        entry.bars
    }

    private fun remember(key: Pair<String, LocalDate>, bars: List<MinuteCandle>) = synchronized(days) {
        days.remove(key)
        days[key] = Remembered(ticker(), bars)
        while (days.size > MAX_ENTRIES) days.remove(days.keys.first())
    }

    companion object {
        private val TTL = Duration.ofDays(4)

        internal const val MAX_ENTRIES = 300

        fun baseCode(stockCode: String) = stockCode.substringBefore("_")
    }
}
