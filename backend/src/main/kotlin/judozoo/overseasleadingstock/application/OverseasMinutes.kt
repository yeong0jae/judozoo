package judozoo.overseasleadingstock.application

import judozoo.library.KST
import judozoo.library.SingleFlight
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.domain.DayCandles
import judozoo.overseasleadingstock.domain.DayCandles.Companion.byDay
import judozoo.overseasleadingstock.domain.OverseasMinuteCandle
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasChart.MinutePagesInterrupted
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.TreeMap

@Component
class OverseasMinutes(
    private val chart: KisOverseasChart,
    private val archive: OverseasMinuteArchive,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal var maxStocks = MAX_STOCKS

    private data class Key(val exchange: String, val symbol: String)

    private class Held(
        var latestDay: LocalDate,
        var latest: TreeMap<LocalDateTime, OverseasMinuteCandle>,
        var syncedAt: LocalDateTime,
        var syncedWhileClosed: Boolean,
        var readAt: LocalDateTime,
    ) {
        fun latestBars() = latest.values.toList()
    }

    private val lock = Any()
    private val stocks = HashMap<Key, Held>()

    private val flight = SingleFlight<Key, List<OverseasMinuteCandle>>()

    fun minuteCandles(exchange: String, symbol: String): List<OverseasMinuteCandle> {
        val key = Key(exchange, symbol)
        val at = now()
        val closed = usClosed()
        val (held, fresh) = synchronized(lock) {
            evict(keep = key)
            val held = stocks[key]
            held?.readAt = at
            held to (held != null && isFresh(held, at, closed))
        }
        if (fresh) return chart(key, held!!)
        return flight.run(key) { refresh(key, at, closed) }
    }

    fun settle(): Int {
        val keys = synchronized(lock) { stocks.keys.toList() }
        var failures = 0
        for (key in keys) {
            val held = synchronized(lock) { stocks[key] } ?: continue
            val fresh = try {
                chart.fetchMinuteCandles(key.exchange, key.symbol, since = held.latest.lastKey().minus(REWRITE))
            } catch (e: Exception) {
                failures++
                log.warn("해외 분봉 마감 확정 실패 ({}:{})", key.exchange, key.symbol, e)
                continue
            }
            val at = now()
            extend(key, held, fresh)
            val latest = synchronized(lock) {
                held.syncedAt = at
                held.syncedWhileClosed = true
                DayCandles(held.latestDay, held.latestBars())
            }
            archive.put(key.exchange, key.symbol, latest.day, latest.candles)
        }
        return failures
    }

    private fun chart(key: Key, held: Held): List<OverseasMinuteCandle> {
        val (day, current) = synchronized(lock) { DayCandles(held.latestDay, held.latestBars()) }
        val archived = archive.recent(key.exchange, key.symbol, KisOverseasChart.SESSION_DAYS)
        val previous = archived.firstOrNull { it.day < day }?.candles.orEmpty()
        return (previous + current).sortedBy { it.dateTime }
    }

    private fun isFresh(held: Held, at: LocalDateTime, closed: Boolean) =
        Duration.between(held.syncedAt, at) <= FRESH ||
            (closed && held.syncedWhileClosed && localDay(at) == localDay(held.syncedAt))

    private fun refresh(key: Key, at: LocalDateTime, closed: Boolean): List<OverseasMinuteCandle> {
        val held = synchronized(lock) { stocks[key] }
        if (held != null && synchronized(lock) { isFresh(held, at, closed) }) return chart(key, held)
        if (held == null) return load(key, at, closed)
        if (ChronoUnit.DAYS.between(held.latestDay, localDay(at)) > ARCHIVE_REACH_DAYS) {
            return try {
                load(key, at, closed).ifEmpty { chart(key, held) }
            } catch (e: Exception) {
                log.warn("해외 분봉 재조회 실패 — 기존 봉 유지 ({}:{})", key.exchange, key.symbol, e)
                chart(key, held)
            }
        }
        val (fresh, complete) = try {
            chart.fetchMinuteCandles(key.exchange, key.symbol, since = held.latest.lastKey().minus(REWRITE)) to true
        } catch (e: MinutePagesInterrupted) {
            e.candles to false
        } catch (e: Exception) {
            log.warn("해외 분봉 이어 받기 실패 — 들고 있던 봉을 준다 ({}:{})", key.exchange, key.symbol, e)
            return chart(key, held)
        }
        if (!complete) {
            val merged = TreeMap(chart(key, held).associateBy { it.dateTime })
            fresh.forEach { merged[it.dateTime] = it }
            val days = merged.values.map { it.tradingDay }.toSortedSet().reversed().take(2).toSet()
            return merged.values.filter { it.tradingDay in days }
        }
        extend(key, held, fresh)
        synchronized(lock) {
            held.syncedAt = at
            held.syncedWhileClosed = closed
        }
        return chart(key, held)
    }

    private fun load(key: Key, at: LocalDateTime, closed: Boolean): List<OverseasMinuteCandle> {
        val archived = archive.recent(key.exchange, key.symbol, KisOverseasChart.SESSION_DAYS)
        if (archived.isNotEmpty() && ChronoUnit.DAYS.between(archived[0].day, localDay(at)) <= ARCHIVE_REACH_DAYS) {
            val last = archived[0].candles.last().dateTime
            val fresh = try {
                chart.fetchMinuteCandles(key.exchange, key.symbol, since = last.plusMinutes(1))
            } catch (e: MinutePagesInterrupted) {
                return (archived[0].candles + e.candles).sortedBy { it.dateTime }
            }
            afterArchive(archived, fresh)?.let { (latest, finished) ->
                finished.forEach { (day, bars) -> archive.put(key.exchange, key.symbol, day, bars) }
                return hold(key, latest, at, closed)
            }
        }

        val candles = try {
            chart.fetchMinuteCandles(key.exchange, key.symbol)
        } catch (e: MinutePagesInterrupted) {
            return e.candles.sortedBy { it.dateTime }
        }
        if (candles.isEmpty()) return emptyList()
        val byDay = byDay(candles)
        val days = byDay.keys.sortedDescending()
        if (days.size > 1) archive.put(key.exchange, key.symbol, days[1], byDay.getValue(days[1]))
        return hold(key, DayCandles(days[0], byDay.getValue(days[0])), at, closed)
    }

    private data class Resumed(val latest: DayCandles, val finished: List<DayCandles>)

    private fun afterArchive(archived: List<DayCandles>, fresh: List<OverseasMinuteCandle>): Resumed? {
        val byDay = archived.associate { it.day to it.candles } + byDay(fresh)
        val days = byDay.keys.sortedDescending()
        if (days.size < 2) return null
        val kept = archived.map { it.day }.toSet()
        val finished = days.drop(1).filterNot { it in kept }.map { DayCandles(it, byDay.getValue(it)) }
        return Resumed(DayCandles(days[0], byDay.getValue(days[0])), finished)
    }

    private fun hold(key: Key, latest: DayCandles, at: LocalDateTime, closed: Boolean): List<OverseasMinuteCandle> {
        val held = Held(latest.day, TreeMap(latest.candles.associateBy { it.dateTime }), at, closed, at)
        synchronized(lock) { stocks[key] = held }
        return chart(key, held)
    }

    private fun extend(key: Key, held: Held, fresh: List<OverseasMinuteCandle>) {
        val merged = synchronized(lock) {
            TreeMap(held.latest).apply { fresh.filter { it.tradingDay >= held.latestDay }.forEach { put(it.dateTime, it) } }
        }
        val byDay = byDay(merged.values.toList())
        val newDay = byDay.keys.max()
        byDay.keys.sorted().filter { it < newDay }.forEach { archive.put(key.exchange, key.symbol, it, byDay.getValue(it)) }
        synchronized(lock) {
            held.latestDay = newDay
            held.latest = TreeMap(byDay.getValue(newDay).associateBy { it.dateTime })
        }
    }

    private fun evict(keep: Key) {
        val overflow = stocks.size + (if (keep in stocks) 0 else 1) - maxStocks
        if (overflow > 0) {
            stocks.entries.filter { it.key != keep }.sortedBy { it.value.readAt }.take(overflow).forEach { stocks.remove(it.key) }
        }
    }

    private fun usClosed(): Boolean = try {
        !calendar.usMarketStatus().inSession
    } catch (e: Exception) {
        false
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun localDay(at: LocalDateTime): LocalDate = Region.US.local(at).toLocalDate()

    companion object {
        private val FRESH = Duration.ofSeconds(30)

        private val REWRITE = Duration.ofMinutes(2)

        private const val MAX_STOCKS = 50

        private const val ARCHIVE_REACH_DAYS = 4L
    }
}
