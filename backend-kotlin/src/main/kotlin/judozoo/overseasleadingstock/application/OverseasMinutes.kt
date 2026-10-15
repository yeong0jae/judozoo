package judozoo.overseasleadingstock.application

import judozoo.library.KST
import judozoo.library.SingleFlight
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasChart.MinutePagesInterrupted
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/**
 * 해외 당일 분봉 저장소와 지난 거래일 보관소를 합쳐 최근 2거래일 차트를 만든다.
 *
 * 종목별 저장소에는 갱신 중인 한 거래일만 둔다. 지난 날은 [OverseasMinuteArchive]가 소유한다.
 * 미국 현지 영업일(tymd)의 새 봉을 완전히 받으면 이전 날을 보관한 뒤 전환한다. 한국 자정에는 초기화하지 않는다.
 */
@Component
class OverseasMinutes(
    private val chart: KisOverseasChart,
    private val archive: OverseasMinuteArchive,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 테스트가 상한을 줄인다. */
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

    /** 같은 종목을 동시에 받으면 한 번만 부른다 — 처음 열기는 KIS 12페이지 안팎이다. 마감 확정은 태우지 않는다. */
    private val flight = SingleFlight<Key, List<OverseasMinuteCandle>>()

    /** 최근 2거래일 1분봉(시각 오름차순). 처음 열 때 받는 게 실패하면 예외를 올린다. */
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

    /**
     * 마감 확정 — 들고 있는 종목의 최신 거래일을 한 번 더 받아 굳힌 뒤 보관소에 넘긴다. 실패한 종목 수를 돌려준다.
     * **들고 있는 종목만** 넘긴다 — 상한으로 정리한 종목은 다음에 열 때 보관소에서 복원한다.
     */
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
                held.latestDay to held.latestBars()
            }
            archive.put(key.exchange, key.symbol, latest.first, latest.second)
        }
        return failures
    }

    /** 지난 봉은 보관소에서 읽고 당일 봉과 응답에서만 합친다. */
    private fun chart(key: Key, held: Held): List<OverseasMinuteCandle> {
        val (day, current) = synchronized(lock) { held.latestDay to held.latestBars() }
        val archived = archive.recent(key.exchange, key.symbol, KisOverseasChart.SESSION_DAYS)
        val previous = archived.firstOrNull { it.first < day }?.second.orEmpty()
        return (previous + current).sortedBy { it.dateTime }
    }

    private fun isFresh(held: Held, at: LocalDateTime, closed: Boolean) =
        Duration.between(held.syncedAt, at) <= FRESH ||
            (closed && held.syncedWhileClosed && localDay(at) == localDay(held.syncedAt))

    /** 처음 열거나 새 봉을 이어 받는다. 선두가 막 받고 끝난 뒤에 들어온 요청은 받지 않고 그 값을 쓴다. */
    private fun refresh(key: Key, at: LocalDateTime, closed: Boolean): List<OverseasMinuteCandle> {
        val held = synchronized(lock) { stocks[key] }
        if (held != null && synchronized(lock) { isFresh(held, at, closed) }) return chart(key, held)
        if (held == null) return load(key, at, closed)
        if (ChronoUnit.DAYS.between(held.latestDay, localDay(at)) > ARCHIVE_REACH_DAYS) {
            // 오래 보관한 종목을 며칠 뒤 다시 열 때 수십 일치를 이어 받지 않는다
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
            e.candles to false // 받은 새 봉은 붙이되, 다음에 다시 받게 둔다
        } catch (e: Exception) {
            log.warn("해외 분봉 이어 받기 실패 — 들고 있던 봉을 준다 ({}:{})", key.exchange, key.symbol, e)
            return chart(key, held)
        }
        if (!complete) {
            // 새 거래일로 전환하다 끊겼으면 전날 확정·저장을 하지 않고 다음 요청에서 재시도한다
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

    /**
     * 처음 여는 종목. 보관소에 끝난 날이 있으면 그 뒤 봉만 받고, 없으면 2거래일치를 받는다.
     * 중간에 끊기면 보여만 주고 들고 있지 않는다(다음에 다시).
     */
    private fun load(key: Key, at: LocalDateTime, closed: Boolean): List<OverseasMinuteCandle> {
        val archived = archive.recent(key.exchange, key.symbol, KisOverseasChart.SESSION_DAYS)
        if (archived.isNotEmpty() && ChronoUnit.DAYS.between(archived[0].first, localDay(at)) <= ARCHIVE_REACH_DAYS) {
            val last = archived[0].second.last().dateTime
            val fresh = try {
                chart.fetchMinuteCandles(key.exchange, key.symbol, since = last.plusMinutes(1))
            } catch (e: MinutePagesInterrupted) {
                return (archived[0].second + e.candles).sortedBy { it.dateTime }
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
        // 받는 쪽이 세 번째 날이 보일 때까지 받으므로 직전 거래일은 온전하다
        if (days.size > 1) archive.put(key.exchange, key.symbol, days[1], byDay.getValue(days[1]))
        return hold(key, days[0] to byDay.getValue(days[0]), at, closed)
    }

    /**
     * 보관소의 날 뒤에 새로 받은 봉을 이어 최신·직전 거래일로 가른다. 두 날이 안 되면 null(처음처럼 받는다).
     * 새로 받은 날 중 최신이 아닌 날은 끝난 날이다 — 함께 돌려줘 보관소에 넘기게 한다.
     */
    private fun afterArchive(
        archived: List<Pair<LocalDate, List<OverseasMinuteCandle>>>,
        fresh: List<OverseasMinuteCandle>,
    ): Pair<Pair<LocalDate, List<OverseasMinuteCandle>>, List<Pair<LocalDate, List<OverseasMinuteCandle>>>>? {
        val byDay = archived.toMap() + byDay(fresh)
        val days = byDay.keys.sortedDescending()
        if (days.size < 2) return null
        val kept = archived.map { it.first }.toSet()
        val finished = days.drop(1).filterNot { it in kept }.map { it to byDay.getValue(it) }
        return (days[0] to byDay.getValue(days[0])) to finished
    }

    private fun hold(key: Key, latest: Pair<LocalDate, List<OverseasMinuteCandle>>, at: LocalDateTime, closed: Boolean): List<OverseasMinuteCandle> {
        val held = Held(latest.first, TreeMap(latest.second.associateBy { it.dateTime }), at, closed, at)
        synchronized(lock) { stocks[key] = held }
        return chart(key, held)
    }

    /** 완성된 이전 날을 보관소에 넘긴 뒤 당일 칸을 새 미국 거래일로 전환한다. */
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

    /** 상한을 넘으면 오래 안 본 종목부터 내려놓는다. 다음에 열 때 보관소에서 복원한다. 락을 쥔 채 부른다. */
    private fun evict(keep: Key) {
        val overflow = stocks.size + (if (keep in stocks) 0 else 1) - maxStocks
        if (overflow > 0) {
            stocks.entries.filter { it.key != keep }.sortedBy { it.value.readAt }.take(overflow).forEach { stocks.remove(it.key) }
        }
    }

    /** 모르면 열려 있다고 본다 — 받는 쪽이 안전하다. */
    private fun usClosed(): Boolean = try {
        val (holiday, tradingHours) = calendar.usMarketStatus()
        holiday || !tradingHours
    } catch (e: Exception) {
        false
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun localDay(at: LocalDateTime): LocalDate = at.atZone(KST).withZoneSameInstant(Region.US.zone).toLocalDate()

    companion object {
        /** 장중에 이보다 오래된 값이면 이어 받는다 — 화면이 30초마다 묻는다. */
        private val FRESH = Duration.ofSeconds(30)

        /** 마지막 봉부터 이만큼 거슬러 다시 받아 덮어쓴다 — 진행 중이던 봉이 그사이 확정됐을 수 있다. */
        private val REWRITE = Duration.ofMinutes(2)

        private const val MAX_STOCKS = 50

        /** 보관소의 최신 날이 이보다 오래됐으면 쓰지 않는다 — 그 뒤를 이어 받으면 2거래일치보다 더 받게 된다. */
        private const val ARCHIVE_REACH_DAYS = 4L

        private fun byDay(candles: List<OverseasMinuteCandle>): Map<LocalDate, List<OverseasMinuteCandle>> =
            candles.sortedBy { it.dateTime }.groupBy { it.tradingDay }
    }
}
