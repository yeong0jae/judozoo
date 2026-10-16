package judozoo.overseasleadingstock.application

import judozoo.overseasleadingstock.domain.OverseasMinuteCandleEntity
import judozoo.overseasleadingstock.domain.OverseasMinuteCandleRepository
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate

@Component
class OverseasMinuteArchive(
    private val repository: OverseasMinuteCandleRepository,
    private val transaction: TransactionTemplate,
    private val ticker: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Key(val exchange: String, val symbol: String, val day: LocalDate)
    private class Remembered(val storedAt: Long, val bars: List<OverseasMinuteCandle>)

    private val days = LinkedHashMap<Key, Remembered>()

    fun recent(exchange: String, symbol: String, limit: Int): List<Pair<LocalDate, List<OverseasMinuteCandle>>> {
        val known = synchronized(days) { days.keys.filter { it.exchange == exchange && it.symbol == symbol }.map { it.day } }
        val remembered = sortedMapOf<LocalDate, List<OverseasMinuteCandle>>()
        known.forEach { day -> recall(Key(exchange, symbol, day))?.let { remembered[day] = it } }
        if (remembered.size < limit) {
            try {
                repository.latestDays(exchange, symbol, Limit.of(limit)).filterNot { it in remembered }.forEach { day ->
                    val bars = repository.findByExchangeAndSymbolAndTradingDayOrderByMinuteAt(exchange, symbol, day).map { it.toCandle() }
                    if (bars.isNotEmpty()) {
                        remember(Key(exchange, symbol, day), bars)
                        remembered[day] = bars
                    }
                }
            } catch (e: Exception) {
                log.warn("해외 지난 날 분봉 DB 조회 실패 ({}:{})", exchange, symbol, e)
            }
        }
        return remembered.entries.reversed().take(limit).map { it.key to it.value }
    }

    fun put(exchange: String, symbol: String, day: LocalDate, bars: List<OverseasMinuteCandle>) {
        if (bars.isEmpty()) return
        val ordered = bars.sortedBy { it.dateTime }
        remember(Key(exchange, symbol, day), ordered)
        try {
            transaction.executeWithoutResult {
                repository.deleteDay(exchange, symbol, day)
                repository.saveAll(ordered.map { entityOf(exchange, symbol, day, it) })
            }
        } catch (e: Exception) {
            log.warn("해외 지난 날 분봉 DB 저장 실패 ({}:{}, {})", exchange, symbol, day, e)
        }
    }

    fun purge(before: LocalDate): Int {
        synchronized(days) { days.keys.removeIf { it.day < before } }
        return transaction.execute { repository.deleteBefore(before) } ?: 0
    }

    private fun recall(key: Key): List<OverseasMinuteCandle>? = synchronized(days) {
        val entry = days[key] ?: return null
        if (ticker() - entry.storedAt > TTL.toNanos()) {
            days.remove(key)
            return null
        }
        entry.bars
    }

    private fun remember(key: Key, bars: List<OverseasMinuteCandle>) = synchronized(days) {
        days.remove(key)
        days[key] = Remembered(ticker(), bars)
        while (days.size > MAX_ENTRIES) days.remove(days.keys.first())
    }

    companion object {
        private val TTL = Duration.ofDays(4)

        private const val MAX_ENTRIES = 200

        private fun OverseasMinuteCandleEntity.toCandle() = OverseasMinuteCandle(
            dateTime = minuteAt, tradingDay = tradingDay,
            open = exact(open), high = exact(high), low = exact(low), close = exact(close),
            volume = volume, tradingValue = exact(tradingValue),
        )

        private fun entityOf(exchange: String, symbol: String, day: LocalDate, c: OverseasMinuteCandle) =
            OverseasMinuteCandleEntity(
                exchange, symbol, day, c.dateTime, c.open.toFloat(), c.high.toFloat(), c.low.toFloat(), c.close.toFloat(),
                c.volume, c.tradingValue.toFloat(),
            )
    }
}
