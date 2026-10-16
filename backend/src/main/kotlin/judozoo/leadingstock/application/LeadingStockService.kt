package judozoo.leadingstock.application

import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.DailyCandles
import judozoo.leadingstock.domain.FilterResult
import judozoo.leadingstock.domain.LeadingStockCriteria
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.LeadingStocks
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.MinuteCandles
import judozoo.leadingstock.domain.StockFilter
import judozoo.library.EntityNotFoundException
import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.application.MarketCalendar
import judozoo.platform.kiwoom.KiwoomMarket
import judozoo.platform.kiwoom.KiwoomProgram
import judozoo.stock.application.StockCatalog
import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Service
class LeadingStockService(
    private val kiwoom: KiwoomMarket,
    private val program: KiwoomProgram,
    private val todayMinutes: TodayMinutes,
    private val archive: StockMinuteArchive,
    private val wideLimitDays: WideLimitDays,
    private val calendar: MarketCalendar,
    private val catalog: StockCatalog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val criteria = LeadingStockCriteria()

    private val pool = TtlCache<Unit, List<LeadingStockSnapshot>>("tradingValuePool", ::poolTtl, maxSize = 1, serveStale = true)

    private val basics = TtlCache<Triple<String, LocalDate, Boolean>, LeadingStockSnapshot?>(
        "stockBasics", Duration.ofHours(24), maxSize = 100, skipIf = { it == null },
    )

    private val evaluationHistory = TtlCache<Pair<String, LocalDate>, List<DailyCandle>>(
        "stockEvaluationHistory", Duration.ofHours(24), maxSize = 100, skipIf = { it.isEmpty() },
    )

    data class StockEvaluation(
        val stock: LeadingStockSnapshot,
        val filterResults: List<FilterResult>,
        val relativeVolume: Double?,
        val market: Market?,
    )

    data class BreakoutRadarStock(
        val stock: LeadingStockSnapshot,
        val peak: MinuteCandles.SwingHigh,
        val trough: MinuteCandles.SwingLow?,
    )

    internal fun poolTtl(): Duration {
        if (calendar.marketStatus().inSession) return POOL_TTL
        val at = now()
        val reopen = at.toLocalDate().atTime(SESSION_START).let { if (at.isBefore(it)) it else it.plusDays(1) }
        return Duration.between(at, reopen)
    }

    fun refreshTradingValuePool() {
        pool.refresh(Unit, ::loadPool)
    }

    fun syncTodayMinutes(): Int = todayMinutes.sync(findCandidates(WATCH_MIN_CHANGE_RATE).map { it.stockCode })

    fun settleTodayMinutes(): Int {
        val (settled, failures) = todayMinutes.settle(findCandidates(WATCH_MIN_CHANGE_RATE).map { it.stockCode })
        val today = today()
        settled.forEach { (code, bars) -> archive.put(code, today, bars) }
        try {
            archive.purge(today.minusDays(MINUTE_ARCHIVE_RETENTION_DAYS))
        } catch (e: Exception) {
            log.warn("지난 날 분봉 정리 실패", e)
        }
        return failures
    }

    fun findCandidates(minDailyPriceChangeRate: Double): List<LeadingStockSnapshot> {
        val effective = criteria.copy(minDailyPriceChangeRate = minDailyPriceChangeRate)
        val filters = listOf(
            StockFilter.EtfExclusion, StockFilter.SpacExclusion,
            StockFilter.TradingValueRank(effective), StockFilter.DailyPriceChange(effective),
        )
        return tradingValuePool().filter { stock -> filters.all { it.passes(stock) } }
    }

    fun findLeaders(count: Int): List<LeadingStockSnapshot> = LeadingStocks(findCandidates(0.0)).leaders(count)

    fun findLimitUps(): List<LeadingStockSnapshot> = LeadingStocks(findCandidates(0.0)).limitUps()

    fun evaluate(stockCode: String): StockEvaluation {
        log.info("종목 평가: {}", stockCode)
        val at = now()
        val today = at.toLocalDate()
        val ranked = tradingValuePool().firstOrNull { it.stockCode == stockCode }
        val history = evaluationHistory.get(stockCode to today) {
            kiwoom.fetchDailyCandles(stockCode, 61).filter { it.date < today }.take(60)
        }

        val stock = if (ranked != null) {
            val basic = basics.get(Triple(stockCode, today, !at.toLocalTime().isBefore(SESSION_START))) { kiwoom.fetchStockDetail(stockCode) }
                ?: throw EntityNotFoundException("종목을 찾을 수 없습니다: $stockCode")
            ranked.copy(marketCap = basic.marketCap, openingPrice = basic.openingPrice, previousClose = basic.previousClose)
        } else {
            kiwoom.fetchStockDetail(stockCode) ?: throw EntityNotFoundException("종목을 찾을 수 없습니다: $stockCode")
        }

        val daily = listOf(DailyCandle(today, stock.openingPrice, 0, 0, stock.currentPrice, 0, stock.priceChangeRate)) + history
        val programNetBuy = program.fetchProgramNetBuy(stockCode)
        val filters = listOf(
            StockFilter.TradingValueRank(criteria),
            StockFilter.DailyPriceChange(criteria),
            StockFilter.DailyHighPosition(criteria, daily, today),
            StockFilter.PriceAboveOpen,
            StockFilter.PrevDayClose(criteria, daily.take(3)),
            StockFilter.OpeningPrice(criteria, daily.take(3)),
            StockFilter.MarketCap(criteria),
            StockFilter.ProgramNetBuy(criteria, programNetBuy),
        )
        val currentVolume = stock.accumulatedVolume?.takeIf { !at.toLocalTime().isBefore(SESSION_START) && calendar.isOpen(today) == true }
        return StockEvaluation(
            stock = stock,
            filterResults = filters.map { it.evaluate(stock) },
            relativeVolume = DailyCandles(history).relativeVolumeFrom(currentVolume, today, RVOL_LOOKBACK_DAYS),
            market = catalog.marketOf(stockCode),
        )
    }

    fun breakoutRadar(): List<BreakoutRadarStock> = findCandidates(WATCH_MIN_CHANGE_RATE).mapNotNull { stock ->
        val candles = MinuteCandles(minuteCandles(stock.stockCode, today()))
        val peak = candles.peakSignal(stock.currentPrice) ?: return@mapNotNull null
        BreakoutRadarStock(stock, peak, candles.troughSignal(stock.currentPrice))
    }.sortedBy { it.peak.gapRate }

    data class SignalReading(val stock: LeadingStockSnapshot, val spike: MinuteCandles.VolumeSpike?)

    fun signalReadings(): List<SignalReading> = findCandidates(WATCH_MIN_CHANGE_RATE).map { stock ->
        val spike = MinuteCandles(todayMinuteCandles(stock.stockCode)).volumeSpike(SPIKE_BASELINE_BARS)
        SignalReading(stock, spike?.takeIf { it.latestTradingValue >= SPIKE_MIN_TRADING_VALUE })
    }

    fun minuteCandles(stockCode: String, on: LocalDate): List<MinuteCandle> {
        val today = today()
        val days = sortedMapOf<LocalDate, List<MinuteCandle>>()
        if (on == today) todayMinuteCandles(stockCode).takeIf { it.isNotEmpty() }?.let { days[today] = it }
        var cursor = if (on == today) on else on.plusDays(1)
        while (days.size < CHART_SESSION_DAYS) {
            val day = calendar.previousOpenDay(cursor) ?: return minuteCandlesByPages(stockCode, on)
            val bars = pastDayMinutes(stockCode, day)
            if (bars.isEmpty()) break
            days[day] = bars
            cursor = day
        }
        return days.values.flatten()
    }

    fun dailyCandles(stockCode: String, on: LocalDate): List<DailyCandle> = kiwoom.fetchDailyCandles(stockCode, 200, on)

    private fun tradingValuePool(): List<LeadingStockSnapshot> = pool.get(Unit, ::loadPool)

    private fun loadPool(): List<LeadingStockSnapshot> = wideLimitDays.mark(kiwoom.fetchTopTradingValueStocks(POOL_SIZE), poolTradeDate(), now())

    private fun poolTradeDate(): LocalDate {
        val holiday = calendar.marketStatus().holiday
        val at = now()
        if (!holiday && !at.toLocalTime().isBefore(SESSION_START)) return at.toLocalDate()
        return calendar.previousOpenDay(at.toLocalDate()) ?: at.toLocalDate()
    }

    private fun pastDayMinutes(stockCode: String, day: LocalDate): List<MinuteCandle> {
        archive.get(stockCode, day)?.let { return it }
        val page = kiwoom.fetchHistoricalMinuteCandles(stockCode, day)
        archive.putPage(stockCode, page)
        return page.filter { it.dateTime.toLocalDate() == day }.sortedBy { it.dateTime }
    }

    private fun minuteCandlesByPages(stockCode: String, on: LocalDate): List<MinuteCandle> {
        val today = today()
        val yesterday = today.minusDays(1)
        val all = (
            if (on == today) todayMinuteCandles(stockCode) + kiwoom.fetchHistoricalMinuteCandles(stockCode, yesterday)
            else kiwoom.fetchHistoricalMinuteCandles(stockCode, on)
        ).toMutableList()

        fun dayCount() = all.map { it.dateTime.toLocalDate() }.toSet().size
        while (all.isNotEmpty() && dayCount() <= CHART_SESSION_DAYS) {
            val oldest = minOf(all.minOf { it.dateTime.toLocalDate() }, yesterday)
            val before = dayCount()
            all += kiwoom.fetchHistoricalMinuteCandles(stockCode, oldest)
            if (dayCount() == before) break
        }

        val recentDays = all.map { it.dateTime.toLocalDate() }.filter { it <= on }.toSortedSet().reversed().take(CHART_SESSION_DAYS).toSet()
        val byTime = sortedMapOf<LocalDateTime, MinuteCandle>()
        all.filter { it.dateTime.toLocalDate() in recentDays }.forEach { byTime.putIfAbsent(it.dateTime, it) }
        return byTime.values.toList()
    }

    private fun todayMinuteCandles(stockCode: String): List<MinuteCandle> {
        val holiday = calendar.marketStatus().holiday
        val at = now()
        if (holiday || at.toLocalTime().isBefore(SESSION_START)) return emptyList()
        val today = at.toLocalDate()
        archive.get(stockCode, today)?.let { return it }
        if (at.toLocalTime().isBefore(SETTLED_AFTER_CLOSE)) return todayMinutes.ensure(stockCode, at.minus(FRESH_DURING_SESSION))
        val closedAt = today.atTime(SETTLED_AFTER_CLOSE)
        val bars = todayMinutes.ensure(stockCode, closedAt)
        if (todayMinutes.syncedSince(stockCode, closedAt)) archive.put(stockCode, today, bars)
        return bars
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = now().toLocalDate()

    companion object {
        private const val POOL_SIZE = 50

        private val POOL_TTL = Duration.ofSeconds(20)

        private val SESSION_START = LocalTime.of(8, 0)

        const val WATCH_MIN_CHANGE_RATE = -12.0

        private const val RVOL_LOOKBACK_DAYS = 20

        private const val SPIKE_BASELINE_BARS = 20

        private const val SPIKE_MIN_TRADING_VALUE = 1_000_000_000L

        private const val MINUTE_ARCHIVE_RETENTION_DAYS = 14L

        private const val CHART_SESSION_DAYS = 3

        private val FRESH_DURING_SESSION = Duration.ofSeconds(30)

        private val SETTLED_AFTER_CLOSE = LocalTime.of(20, 1)
    }
}
