package judozoo.market.application

import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.domain.Region
import judozoo.market.infrastructure.FuturesMinuteArchive
import judozoo.market.infrastructure.IndexMinuteArchive
import judozoo.market.infrastructure.YahooMinuteArchive
import judozoo.platform.kis.KisFutures
import judozoo.platform.kis.KisFutures.FuturesBar
import judozoo.platform.toss.TossMarketIndicator
import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.platform.yahoo.YahooClient
import judozoo.platform.yahoo.YahooClient.YahooBar
import judozoo.stock.domain.Market
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Service
class MarketCandles(
    private val toss: TossMarketIndicator,
    private val futures: KisFutures,
    private val contracts: FuturesContracts,
    private val yahoo: YahooClient,
    private val calendar: MarketCalendar,
    private val indexArchive: IndexMinuteArchive,
    private val futuresArchive: FuturesMinuteArchive,
    private val yahooArchive: YahooMinuteArchive,
    private val clock: Clock,
) {
    private fun <K : Any, V : List<*>> cache(name: String, maxSize: Int) = TtlCache<K, V>(name, Duration.ofSeconds(60), maxSize = maxSize, skipIf = { it.isEmpty() })

    private val indexDaily = cache<Pair<Market, Int>, List<TossCandle>>("indexDailyCandles", 4)
    private val indexMinute = cache<Market, List<TossCandle>>("indexMinuteCandles", 2)
    private val futuresDaily = cache<Pair<Market, Int>, List<FuturesBar>>("futuresDailyCandles", 4)
    private val futuresMinute = cache<Market, List<FuturesBar>>("futuresMinuteCandles", 2)
    private val nightDaily = cache<Int, List<FuturesBar>>("nightFuturesDailyCandles", 2)
    private val nightMinute = cache<Unit, List<FuturesBar>>("nightFuturesMinuteCandles", 1)
    private val yahooDaily = cache<String, List<YahooBar>>("yahooDailyCandles", 6)
    private val yahooMinute = cache<String, List<YahooBar>>("yahooMinuteCandles", 6)

    fun indexDaily(market: Market, count: Int): List<TossCandle> = indexDaily.get(market to count) {
        toss.fetchCandles(market.name, "1d", count.coerceIn(1, MAX_PAGE_SIZE)).candles.sortedBy { it.timestamp }
    }

    fun indexMinute(market: Market): List<TossCandle> = indexMinute.get(market) { loadIndexMinute(market) }

    fun futuresDaily(market: Market, count: Int): List<FuturesBar> = futuresDaily.get(market to count) {
        val near = contracts.nearMonth(market) ?: return@get emptyList()
        val today = today()
        val from = today.minusDays(count * 2L + 10)
        (futures.fetchDaily(near.iscd, from, today) ?: contracts.changed(market, near.iscd)?.let { futures.fetchDaily(it.iscd, from, today) })
            ?.candles.orEmpty()
    }

    fun futuresMinute(market: Market): List<FuturesBar> = futuresMinute.get(market) {
        contracts.nearMonth(market)?.let { recentFuturesMinutes(market, it.iscd) }.orEmpty()
    }

    fun nightDaily(count: Int): List<FuturesBar> = nightDaily.get(count) {
        val near = contracts.nearMonth(Market.KOSPI) ?: return@get emptyList()
        val today = today()
        val from = today.minusDays(count * 2L + 10)
        (futures.fetchDaily(near.iscd, from, today, KisFutures.NIGHT)
            ?: contracts.changed(Market.KOSPI, near.iscd)?.let { futures.fetchDaily(it.iscd, from, today, KisFutures.NIGHT) })
            ?.candles.orEmpty()
    }

    fun nightMinute(): List<FuturesBar> = nightMinute.get(Unit) {
        contracts.nearMonth(Market.KOSPI)?.let { recentNightSession(it.iscd) }.orEmpty()
    }

    fun nasdaq(interval: String): List<YahooBar> = yahooCandles(MarketQuotes.NASDAQ_SYMBOL, interval)

    fun nasdaqFutures(interval: String): List<YahooBar> = when (interval) {
        "1d" -> yahooDaily.get(MarketQuotes.NASDAQ_FUTURES_SYMBOL) { yahoo.fetchCandles(MarketQuotes.NASDAQ_FUTURES_SYMBOL, "1d", "6mo") }
        "1m" -> yahooMinute.get(MarketQuotes.NASDAQ_FUTURES_SYMBOL) { yahoo.fetchCandles(MarketQuotes.NASDAQ_FUTURES_SYMBOL, "1m", "1d") }
        else -> emptyList()
    }

    fun macro(target: String, interval: String): List<YahooBar> =
        MarketQuotes.MACRO_SYMBOLS[target]?.let { yahooCandles(it, interval) }.orEmpty()

    private fun yahooCandles(symbol: String, interval: String): List<YahooBar> = when (interval) {
        "1d" -> yahooDaily.get(symbol) { yahoo.fetchCandles(symbol, "1d", "6mo") }
        "1m" -> yahooMinute.get(symbol) { yahooMinuteCandles(symbol) }
        else -> emptyList()
    }

    private fun loadIndexMinute(market: Market): List<TossCandle> {
        val today = today()
        val byDay = sortedMapOf<LocalDate, MutableMap<LocalDateTime, TossCandle>>()
        val archived = mutableSetOf<LocalDate>()
        val checked = mutableSetOf<LocalDate>()
        var before: String? = null
        var exhausted = false

        for (page in 0 until MAX_PAGES) {
            val result = toss.fetchCandles(market.name, "1m", MAX_PAGE_SIZE, before)
            if (result.candles.isEmpty()) break
            result.candles.forEach { byDay.getOrPut(it.timestamp.toLocalDate()) { mutableMapOf() }[it.timestamp] = it }
            byDay.keys.filter { it < today && it !in checked }.forEach { day ->
                checked += day
                indexArchive.get(market, day)?.takeIf { it.isNotEmpty() }?.let { stored ->
                    byDay[day] = stored.associateByTo(mutableMapOf()) { it.timestamp }
                    archived += day
                }
            }
            exhausted = result.nextBefore == null
            val latest = byDay.keys.sortedDescending().take(2)
            if (byDay.size > 2 || exhausted || (latest.size == 2 && latest.last() in archived)) break
            before = result.nextBefore
        }

        if (byDay.isEmpty()) return emptyList()
        val earliest = byDay.firstKey()
        byDay.forEach { (day, candles) ->
            if (day < today && day !in archived && (day > earliest || exhausted)) indexArchive.put(market, day, candles.values.toList())
        }
        return byDay.keys.sortedDescending().take(2).flatMap { byDay.getValue(it).values }.sortedBy { it.timestamp }
    }

    private fun recentFuturesMinutes(market: Market, iscd: String): List<FuturesBar> {
        val bars = mutableListOf<FuturesBar>()
        val today = today()
        var day = today
        if (now().toLocalTime().isBefore(FUT_SESSION_START) || calendar.isOpen(today) == false) day = calendar.previousOpenDay(today) ?: today
        var collected = 0
        repeat(MINUTE_DAYS + 5) {
            if (collected == MINUTE_DAYS) return@repeat
            var dayBars = if (day < today) futuresArchive.get(market, iscd, false, day).orEmpty() else emptyList()
            if (dayBars.isEmpty()) {
                val (fetched, complete) = futuresMinutesOfDay(iscd, day)
                dayBars = fetched
                if (fetched.isNotEmpty()) {
                    val actual = LocalDate.parse(fetched[0].date)
                    if (actual < today) {
                        val archived = futuresArchive.get(market, iscd, false, actual)
                        if (!archived.isNullOrEmpty()) dayBars = archived else if (complete) futuresArchive.put(market, iscd, false, actual, fetched)
                    }
                }
            }
            if (dayBars.isEmpty()) {
                day = day.minusDays(1)
                return@repeat
            }
            bars += dayBars
            collected++
            day = LocalDate.parse(dayBars[0].date).minusDays(1)
        }
        return bars.sortedBy { it.date + it.time }
    }

    private fun futuresMinutesOfDay(iscd: String, day: LocalDate): Pair<List<FuturesBar>, Boolean> {
        val byTime = sortedMapOf<String, FuturesBar>()
        var hour = FUT_SESSION_END
        for (page in 0 until MINUTE_PAGES) {
            val result = futures.fetchMinute(iscd, day, hour)
            if (result.isEmpty()) break
            result.forEach { byTime[it.time] = it }
            val earliest = LocalTime.parse(result[0].time)
            if (!earliest.isAfter(FUT_SESSION_START)) return byTime.values.toList() to true
            hour = earliest.minusMinutes(1)
        }
        return byTime.values.toList() to false
    }

    private fun recentNightSession(iscd: String): List<FuturesBar> {
        val at = now()
        var day = if (at.hour >= 18) at.toLocalDate() else at.toLocalDate().minusDays(1)
        if (calendar.isOpen(day) == false) day = calendar.previousOpenDay(day.plusDays(1)) ?: day
        repeat(5) {
            val completed = !at.isBefore(day.plusDays(1).atTime(NIGHT_END_HOUR, 0))
            if (completed) futuresArchive.get(Market.KOSPI, iscd, true, day)?.takeIf { it.isNotEmpty() }?.let { return it }
            val (bars, complete) = nightSessionMinutes(iscd, day)
            if (bars.isNotEmpty()) {
                val first = bars[0]
                var actual = LocalDate.parse(first.date)
                if (first.time < "%02d:00:00".format(NIGHT_END_HOUR)) actual = actual.minusDays(1)
                if (actual != day) futuresArchive.get(Market.KOSPI, iscd, true, actual)?.takeIf { it.isNotEmpty() }?.let { return it }
                if (complete && !at.isBefore(actual.plusDays(1).atTime(NIGHT_END_HOUR, 0))) futuresArchive.put(Market.KOSPI, iscd, true, actual, bars)
                return bars
            }
            day = day.minusDays(1)
        }
        return emptyList()
    }

    private fun nightSessionMinutes(iscd: String, day: LocalDate): Pair<List<FuturesBar>, Boolean> {
        val bars = sortedMapOf<String, FuturesBar>()
        var minute = NIGHT_END_MIN
        for (page in 0 until NIGHT_PAGES) {
            val result = futures.fetchMinute(iscd, day, hhmmss(minute), KisFutures.NIGHT)
            if (result.isEmpty()) break
            result.forEach { bars["${it.date} ${it.time}"] = it }
            val earliest = extendedMinute(result[0])
            if (earliest <= NIGHT_START_MIN) return bars.values.toList() to true
            minute = earliest - 1
        }
        return bars.values.toList() to false
    }

    private fun yahooMinuteCandles(symbol: String): List<YahooBar> {
        val current = sessionDay(symbol, clock.instant().atZone(Region.US.zone).toLocalDateTime())
        val archived = yahooArchive.recent(symbol, current)
        var previous = current.minusDays(1)
        while (previous.dayOfWeek == DayOfWeek.SATURDAY || previous.dayOfWeek == DayOfWeek.SUNDAY) previous = previous.minusDays(1)
        val fresh = yahoo.fetchCandles(symbol, "1m", if (previous in archived) "1d" else "2d")
        val byDay = fresh.groupByTo(LinkedHashMap()) { sessionDay(symbol, nyAt(it)) }
        byDay.forEach { (day, bars) ->
            if (day < current && day !in archived && completeYahooDay(symbol, day, bars)) {
                yahooArchive.put(symbol, day, bars)
                archived[day] = bars
            }
        }
        val merged: Map<LocalDate, List<YahooBar>> = byDay + archived
        return merged.keys.sortedDescending().take(2).flatMap { merged.getValue(it) }.sortedBy { it.date + it.time }
    }

    private fun sessionDay(symbol: String, ny: LocalDateTime): LocalDate {
        val rollover = YAHOO_ROLLOVER[symbol]
        return if (rollover != null && !ny.toLocalTime().isBefore(rollover)) ny.toLocalDate().plusDays(1) else ny.toLocalDate()
    }

    private fun completeYahooDay(symbol: String, day: LocalDate, bars: List<YahooBar>): Boolean {
        if (bars.isEmpty()) return false
        val rollover = YAHOO_ROLLOVER[symbol]
        val (start, end) = if (rollover != null) {
            day.minusDays(1).atTime(rollover).plusMinutes(30) to day.atTime(rollover).minusMinutes(if (symbol == "CL=F") 90 else 30)
        } else {
            val (open, close) = if (symbol == "^TNX") LocalTime.of(8, 20) to LocalTime.of(15, 0) else LocalTime.of(9, 30) to LocalTime.of(15, 59)
            val grace = if (symbol == MarketQuotes.NASDAQ_SYMBOL) 0L else 30L
            day.atTime(open).plusMinutes(grace) to day.atTime(close).minusMinutes(grace)
        }
        val times = bars.map(::nyAt)
        return !times.min().isAfter(start) && !times.max().isBefore(end)
    }

    private fun nyAt(bar: YahooBar): LocalDateTime =
        LocalDateTime.parse("${bar.date}T${bar.time}").atZone(KST).withZoneSameInstant(Region.US.zone).toLocalDateTime()

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = LocalDate.now(clock.withZone(KST))

    companion object {
        private const val MAX_PAGE_SIZE = 200

        private const val MAX_PAGES = 6

        private const val MINUTE_DAYS = 2

        private const val MINUTE_PAGES = 7

        private val FUT_SESSION_START = LocalTime.of(8, 45)

        private val FUT_SESSION_END = LocalTime.of(15, 45)

        private const val NIGHT_END_HOUR = 6
        private const val NIGHT_START_MIN = 18 * 60

        private const val NIGHT_END_MIN = 29 * 60 + 59

        private const val NIGHT_PAGES = 8

        private val YAHOO_ROLLOVER = mapOf("KRW=X" to LocalTime.of(17, 0), "CL=F" to LocalTime.of(18, 0))

        private fun extendedMinute(bar: FuturesBar): Int {
            val (h, m) = bar.time.split(":").take(2).map(String::toInt)
            return (if (h < NIGHT_END_HOUR) h + 24 else h) * 60 + m
        }

        private fun hhmmss(minute: Int) = "%02d%02d00".format(minute / 60, minute % 60)
    }
}
