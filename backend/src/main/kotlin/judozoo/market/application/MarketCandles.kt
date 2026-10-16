package judozoo.market.application

import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.library.previousWeekday
import judozoo.market.domain.CandleInterval
import judozoo.market.domain.FuturesSession
import judozoo.market.domain.Indicator
import judozoo.market.domain.MarketCandle
import judozoo.market.domain.Region
import judozoo.market.infrastructure.FuturesMinuteArchive
import judozoo.market.infrastructure.IndexMinuteArchive
import judozoo.market.infrastructure.YahooMinuteArchive
import judozoo.platform.kis.KisFutures
import judozoo.platform.toss.TossMarketIndicator
import judozoo.platform.yahoo.YahooClient
import judozoo.stock.domain.Market
import org.springframework.stereotype.Service
import java.time.Clock
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
    private fun <K : Any> cache(name: String, maxSize: Int) =
        TtlCache<K, List<MarketCandle>>(name, Duration.ofSeconds(60), maxSize = maxSize, skipIf = { it.isEmpty() })

    private val indexDaily = cache<Pair<Market, Int>>("indexDailyCandles", 4)
    private val indexMinute = cache<Market>("indexMinuteCandles", 2)
    private val futuresDaily = cache<Pair<Market, Int>>("futuresDailyCandles", 4)
    private val futuresMinute = cache<Market>("futuresMinuteCandles", 2)
    private val nightDaily = cache<Int>("nightFuturesDailyCandles", 2)
    private val nightMinute = cache<Unit>("nightFuturesMinuteCandles", 1)
    private val yahooDaily = cache<Indicator>("yahooDailyCandles", 6)
    private val yahooMinute = cache<Indicator>("yahooMinuteCandles", 6)

    fun index(market: Market, interval: CandleInterval, count: Int): List<MarketCandle> = when (interval) {
        CandleInterval.DAILY -> indexDaily(market, count)
        CandleInterval.MINUTE -> indexMinute(market)
    }

    fun futures(market: Market, interval: CandleInterval, count: Int): List<MarketCandle> = when (interval) {
        CandleInterval.DAILY -> futuresDaily(market, count)
        CandleInterval.MINUTE -> futuresMinute(market)
    }

    fun nightFutures(interval: CandleInterval, count: Int): List<MarketCandle> = when (interval) {
        CandleInterval.DAILY -> nightDaily(count)
        CandleInterval.MINUTE -> nightMinute()
    }

    fun indicator(indicator: Indicator, interval: CandleInterval): List<MarketCandle> = when (interval) {
        CandleInterval.DAILY -> yahooDaily.get(indicator) { yahoo.fetchCandles(indicator.symbol, interval.code, "6mo") }
        CandleInterval.MINUTE -> yahooMinute.get(indicator) {
            if (indicator.keepsSessions) yahooMinuteCandles(indicator) else yahoo.fetchCandles(indicator.symbol, interval.code, "1d")
        }
    }

    fun indexDaily(market: Market, count: Int): List<MarketCandle> = indexDaily.get(market to count) {
        toss.fetchCandles(market.name, CandleInterval.DAILY.code, count.coerceIn(1, MAX_PAGE_SIZE)).candles.sortedBy { it.at }
    }

    fun indexMinute(market: Market): List<MarketCandle> = indexMinute.get(market) { loadIndexMinute(market) }

    fun futuresDaily(market: Market, count: Int): List<MarketCandle> = futuresDaily.get(market to count) {
        dailyOfNearMonth(market, count, FuturesSession.DAY)
    }

    fun futuresMinute(market: Market): List<MarketCandle> = futuresMinute.get(market) {
        contracts.nearMonth(market)?.let { recentFuturesMinutes(market, it.iscd) }.orEmpty()
    }

    fun nightDaily(count: Int): List<MarketCandle> = nightDaily.get(count) { dailyOfNearMonth(Market.KOSPI, count, FuturesSession.NIGHT) }

    fun nightMinute(): List<MarketCandle> = nightMinute.get(Unit) {
        contracts.nearMonth(Market.KOSPI)?.let { recentNightSession(it.iscd) }.orEmpty()
    }

    private fun dailyOfNearMonth(market: Market, count: Int, session: FuturesSession): List<MarketCandle> {
        val near = contracts.nearMonth(market) ?: return emptyList()
        val today = today()
        val from = today.minusDays(count * 2L + 10)
        return (futures.fetchDaily(near.iscd, from, today, session) ?: contracts.changed(market, near.iscd)?.let { futures.fetchDaily(it.iscd, from, today, session) })
            ?.candles.orEmpty()
    }

    private fun loadIndexMinute(market: Market): List<MarketCandle> {
        val today = today()
        val byDay = sortedMapOf<LocalDate, MutableMap<LocalDateTime, MarketCandle>>()
        val archived = mutableSetOf<LocalDate>()
        val checked = mutableSetOf<LocalDate>()
        var before: String? = null
        var exhausted = false

        for (page in 0 until MAX_PAGES) {
            val result = toss.fetchCandles(market.name, CandleInterval.MINUTE.code, MAX_PAGE_SIZE, before)
            if (result.candles.isEmpty()) break
            result.candles.forEach { byDay.getOrPut(it.at.toLocalDate()) { mutableMapOf() }[it.at] = it }
            byDay.keys.filter { it < today && it !in checked }.forEach { day ->
                checked += day
                indexArchive.get(market, day)?.takeIf { it.isNotEmpty() }?.let { stored ->
                    byDay[day] = stored.associateByTo(mutableMapOf()) { it.at }
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
        return byDay.keys.sortedDescending().take(2).flatMap { byDay.getValue(it).values }.sortedBy { it.at }
    }

    private fun recentFuturesMinutes(market: Market, iscd: String): List<MarketCandle> {
        val bars = mutableListOf<MarketCandle>()
        val today = today()
        var day = today
        if (now().toLocalTime().isBefore(FUT_SESSION_START) || calendar.isOpen(today) == false) day = calendar.previousOpenDay(today) ?: today
        var collected = 0
        repeat(MINUTE_DAYS + 5) {
            if (collected == MINUTE_DAYS) return@repeat
            var dayBars = if (day < today) futuresArchive.get(market, iscd, FuturesSession.DAY, day).orEmpty() else emptyList()
            if (dayBars.isEmpty()) {
                val (fetched, complete) = futuresMinutesOfDay(iscd, day)
                dayBars = fetched
                if (fetched.isNotEmpty()) {
                    val actual = fetched[0].at.toLocalDate()
                    if (actual < today) {
                        val archived = futuresArchive.get(market, iscd, FuturesSession.DAY, actual)
                        if (!archived.isNullOrEmpty()) dayBars = archived else if (complete) futuresArchive.put(market, iscd, FuturesSession.DAY, actual, fetched)
                    }
                }
            }
            if (dayBars.isEmpty()) {
                day = day.minusDays(1)
                return@repeat
            }
            bars += dayBars
            collected++
            day = dayBars[0].at.toLocalDate().minusDays(1)
        }
        return bars.sortedBy { it.at }
    }

    private fun futuresMinutesOfDay(iscd: String, day: LocalDate): Pair<List<MarketCandle>, Boolean> {
        val byTime = sortedMapOf<LocalDateTime, MarketCandle>()
        var hour = FUT_SESSION_END
        for (page in 0 until MINUTE_PAGES) {
            val result = futures.fetchMinute(iscd, day, hour)
            if (result.isEmpty()) break
            result.forEach { byTime[it.at] = it }
            val earliest = result[0].at.toLocalTime()
            if (!earliest.isAfter(FUT_SESSION_START)) return byTime.values.toList() to true
            hour = earliest.minusMinutes(1)
        }
        return byTime.values.toList() to false
    }

    private fun recentNightSession(iscd: String): List<MarketCandle> {
        val at = now()
        var day = if (at.hour >= 18) at.toLocalDate() else at.toLocalDate().minusDays(1)
        if (calendar.isOpen(day) == false) day = calendar.previousOpenDay(day.plusDays(1)) ?: day
        repeat(5) {
            val completed = !at.isBefore(day.plusDays(1).atTime(NIGHT_END))
            if (completed) futuresArchive.get(Market.KOSPI, iscd, FuturesSession.NIGHT, day)?.takeIf { it.isNotEmpty() }?.let { return it }
            val (bars, complete) = nightSessionMinutes(iscd, day)
            if (bars.isNotEmpty()) {
                val first = bars[0].at
                val actual = if (first.toLocalTime().isBefore(NIGHT_END)) first.toLocalDate().minusDays(1) else first.toLocalDate()
                if (actual != day) futuresArchive.get(Market.KOSPI, iscd, FuturesSession.NIGHT, actual)?.takeIf { it.isNotEmpty() }?.let { return it }
                if (complete && !at.isBefore(actual.plusDays(1).atTime(NIGHT_END))) futuresArchive.put(Market.KOSPI, iscd, FuturesSession.NIGHT, actual, bars)
                return bars
            }
            day = day.minusDays(1)
        }
        return emptyList()
    }

    private fun nightSessionMinutes(iscd: String, day: LocalDate): Pair<List<MarketCandle>, Boolean> {
        val bars = sortedMapOf<LocalDateTime, MarketCandle>()
        var minute = NIGHT_END_MIN
        for (page in 0 until NIGHT_PAGES) {
            val result = futures.fetchMinute(iscd, day, hhmmss(minute), FuturesSession.NIGHT)
            if (result.isEmpty()) break
            result.forEach { bars[it.at] = it }
            val earliest = extendedMinute(result[0])
            if (earliest <= NIGHT_START_MIN) return bars.values.toList() to true
            minute = earliest - 1
        }
        return bars.values.toList() to false
    }

    private fun yahooMinuteCandles(indicator: Indicator): List<MarketCandle> {
        val current = indicator.sessionDay(clock.instant().atZone(Region.US.zone).toLocalDateTime())
        val archived = yahooArchive.recent(indicator.symbol, current)
        val previous = current.previousWeekday()
        val fresh = yahoo.fetchCandles(indicator.symbol, CandleInterval.MINUTE.code, if (previous in archived) "1d" else "2d")
        val byDay = fresh.groupByTo(LinkedHashMap()) { indicator.sessionDay(Region.US.local(it.at)) }
        byDay.forEach { (day, bars) ->
            if (day < current && day !in archived && indicator.completes(day, bars.map { Region.US.local(it.at) })) {
                yahooArchive.put(indicator.symbol, day, bars)
                archived[day] = bars
            }
        }
        val merged: Map<LocalDate, List<MarketCandle>> = byDay + archived
        return merged.keys.sortedDescending().take(2).flatMap { merged.getValue(it) }.sortedBy { it.at }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = LocalDate.now(clock.withZone(KST))

    companion object {
        private const val MAX_PAGE_SIZE = 200

        private const val MAX_PAGES = 6

        private const val MINUTE_DAYS = 2

        private const val MINUTE_PAGES = 7

        private val FUT_SESSION_START = LocalTime.of(8, 45)

        private val FUT_SESSION_END = LocalTime.of(15, 45)

        private val NIGHT_END = LocalTime.of(6, 0)
        private const val NIGHT_START_MIN = 18 * 60

        private const val NIGHT_END_MIN = 29 * 60 + 59

        private const val NIGHT_PAGES = 8

        private fun extendedMinute(bar: MarketCandle): Int {
            val time = bar.at.toLocalTime()
            return (if (time.isBefore(NIGHT_END)) time.hour + 24 else time.hour) * 60 + time.minute
        }

        private fun hhmmss(minute: Int) = "%02d%02d00".format(minute / 60, minute % 60)
    }
}
