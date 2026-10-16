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

/**
 * 국내 주도주 — 후보 발굴·평가, 저항·지지, 종목 분봉·일봉.
 *
 * 거래대금 순위 + 당일 등락률로 후보를 추리고, 상세 보기에서 전체 필터를 평가한다.
 * 분봉은 차트·돌파선이 **같은 저장소를 공유**하도록 한 경로로 모은다 — 따로 부르면 키움 한도에 걸린다.
 *
 */
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

    /** 후보 목록·주도주·상세 평가가 같은 응답을 나눠 쓴다. 폴러가 갱신하는 캐시라 만료돼도 마지막 정상값을 준다. */
    private val pool = TtlCache<Unit, List<LeadingStockSnapshot>>("tradingValuePool", ::poolTtl, maxSize = 1, serveStale = true)

    /** 시총·시가·전일 종가. 개장 전에 읽은 값은 08:00 이후 한 번 갱신한다(키에 "개장 뒤인가"를 넣는다). */
    private val basics = TtlCache<Triple<String, LocalDate, Boolean>, LeadingStockSnapshot?>(
        "stockBasics", Duration.ofHours(24), maxSize = 100, skipIf = { it == null },
    )

    /** 상세 조건용 전일까지 최근 60거래일. 차트의 당일 일봉 캐시와 수명을 분리한다. */
    private val evaluationHistory = TtlCache<Pair<String, LocalDate>, List<DailyCandle>>(
        "stockEvaluationHistory", Duration.ofHours(24), maxSize = 100, skipIf = { it.isEmpty() },
    )

    /** 종목 상세 평가 — 필터 평가 + 상대거래량 + 소속 시장. 데이터 없으면 각각 null. */
    data class StockEvaluation(
        val stock: LeadingStockSnapshot,
        val filterResults: List<FilterResult>,
        val relativeVolume: Double?,
        val market: Market?,
    )

    /** 저항·지지 한 종목 — 최근 3거래일 고가(저항선)·저가(지지선) 대비 현재가 갭. 저항은 위로, 지지는 아래로 남은 거리다. */
    data class BreakoutRadarStock(
        val stock: LeadingStockSnapshot,
        val peak: MinuteCandles.SwingHigh,
        val trough: MinuteCandles.SwingLow?,
    )

    /** 장중엔 짧게, 장이 멈춘 동안(장 밖·휴장)은 값이 안 바뀌니 다음 08:00까지 들고 있는다. */
    internal fun poolTtl(): Duration {
        val (holiday, tradingHours) = calendar.marketStatus()
        if (!holiday && tradingHours) return POOL_TTL
        val at = now()
        val reopen = at.toLocalDate().atTime(SESSION_START).let { if (at.isBefore(it)) it else it.plusDays(1) }
        return Duration.between(at, reopen)
    }

    /** 거래대금 상위를 만료 전에 새로 받아 갈아 끼운다 — 장중 갱신 폴러가 부른다. */
    fun refreshTradingValuePool() {
        pool.refresh(Unit, ::loadPool)
    }

    /** 감시 풀(시그널·돌파와 같은 풀)의 당일 분봉을 이어 받는다. 실패한 종목 수를 돌려준다. */
    fun syncTodayMinutes(): Int = todayMinutes.sync(findCandidates(WATCH_MIN_CHANGE_RATE).map { it.stockCode })

    /**
     * 마감 뒤 감시 풀의 오늘 봉을 확정해 지난 날 보관소에 넘긴다. 실패한 종목 수를 돌려준다.
     *
     * 내일 아침 이 종목들의 "어제 봉"을 키움에서 다시 받지 않게 하려는 것이다. 수정주가 소급이 반영되지 않는 봉을
     * 오래 두지 않게 2주 지난 날은 여기서 하루 한 번 지운다.
     */
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

    /**
     * 1단계 필터만 적용한 후보(ETF·스팩 제외 + 거래대금 순위 + 당일 등락률). 거래대금 내림차순.
     *
     * **따로 캐시하지 않는다** — 매번 걸러도 금방이고, 캐시를 두면 풀이 바뀐 뒤에도 옛 풀에 머문다.
     */
    fun findCandidates(minDailyPriceChangeRate: Double): List<LeadingStockSnapshot> {
        val effective = criteria.copy(minDailyPriceChangeRate = minDailyPriceChangeRate)
        val filters = listOf(
            StockFilter.EtfExclusion, StockFilter.SpacExclusion,
            StockFilter.TradingValueRank(effective), StockFilter.DailyPriceChange(effective),
        )
        return tradingValuePool().filter { stock -> filters.all { it.passes(stock) } }
    }

    /**
     * 거래대금·등락률이 함께 높은 상위 [count]개 — 첫 화면이 쓴다.
     *
     * **사용자가 고른 등락률과 무관하다** — 보는 사람 기준에 따라 주도주가 달라지면 안 된다.
     * 임계값 0으로 오른 종목 전부를 받아 점수로 다시 세운다.
     */
    fun findLeaders(count: Int): List<LeadingStockSnapshot> = LeadingStocks(findCandidates(0.0)).leaders(count)

    /**
     * 후보 풀 안의 상한가 — 첫 화면 주도주 카드가 쓴다.
     *
     * 상한가는 거래가 말라 점수가 낮으므로 **탑5가 아니라 후보 풀 전체를 본다.** 후보 컷이 거래대금 35위라 **시장 전체 상한가가 아니다**.
     */
    fun findLimitUps(): List<LeadingStockSnapshot> = LeadingStocks(findCandidates(0.0)).limitUps()

    /** 모든 필터 평가 + 상대거래량 — 상세 보기용. 나열 순서가 곧 화면 표시 순서다. */
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
            // 후보 풀 밖의 종목도 과거 시그널 등에서 열 수 있다. 그때만 기본정보로 시세를 받는다
            kiwoom.fetchStockDetail(stockCode) ?: throw EntityNotFoundException("종목을 찾을 수 없습니다: $stockCode")
        }

        // 전일·시가 필터는 최신 봉을 오늘로 간주한다. 변하지 않는 값만 합성한다
        val daily = listOf(DailyCandle(today, stock.openingPrice, 0, 0, stock.currentPrice, 0, stock.priceChangeRate)) + history
        // 필터는 결과를 만들며 통과 여부를 다시 계산한다 — 키움을 두 번 부르지 않게 값을 먼저 받아 넘긴다
        val programNetBuy = program.fetchProgramNetBuy(stockCode)
        // 판별력이 큰 것부터 — 주도주를 정의하는 둘 → 진입 자리 → 과열 배제(상한) → 기준이 느슨해 대부분 통과하는 둘
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
        // 장 전·휴장일의 누적 거래량은 지난 거래일 값이라 오늘 RVOL로 쓰지 않는다
        val currentVolume = stock.accumulatedVolume?.takeIf { !at.toLocalTime().isBefore(SESSION_START) && calendar.isOpen(today) == true }
        return StockEvaluation(
            stock = stock,
            filterResults = filters.map { it.evaluate(stock) },
            relativeVolume = DailyCandles(history).relativeVolumeFrom(currentVolume, today, RVOL_LOOKBACK_DAYS),
            // 소속 시장은 종목 카탈로그가 안다 — 시세에는 없는 정보다
            market = catalog.marketOf(stockCode),
        )
    }

    /**
     * 감시 풀을 **돌파선 근접 순**으로 정렬한다. 눌림선은 같은 행에 함께 싣는다.
     *
     * **등락률로 거르지 않는다**(-12%까지) — 눌림은 원래 내린 종목에서 나오는 신호다.
     */
    fun breakoutRadar(): List<BreakoutRadarStock> = findCandidates(WATCH_MIN_CHANGE_RATE).mapNotNull { stock ->
        val candles = MinuteCandles(minuteCandles(stock.stockCode, today()))
        val peak = candles.peakSignal(stock.currentPrice) ?: return@mapNotNull null
        // 지지선은 같은 분봉에서 대칭으로 뽑는다. 없더라도 저항은 보여준다
        BreakoutRadarStock(stock, peak, candles.troughSignal(stock.currentPrice))
    }.sortedBy { it.peak.gapRate }

    /** 시그널 판정용 후보 한 종목 — 최신 1분봉 거래대금 스파이크. 거래대금 임계에 못 미치거나 봉이 없으면 [spike]는 null. */
    data class SignalReading(val stock: LeadingStockSnapshot, val spike: MinuteCandles.VolumeSpike?)

    /**
     * 감시 풀의 스파이크를 읽는다 — 시그널 폴러가 쓴다. 스파이크는 **당일 봉끼리만** 비교한다.
     *
     * 임계 거래대금 미만이면 null로 둬야 히스테리시스 해제가 동작한다.
     */
    fun signalReadings(): List<SignalReading> = findCandidates(WATCH_MIN_CHANGE_RATE).map { stock ->
        val spike = MinuteCandles(todayMinuteCandles(stock.stockCode)).volumeSpike(SPIKE_BASELINE_BARS)
        SignalReading(stock, spike?.takeIf { it.latestTradingValue >= SPIKE_MIN_TRADING_VALUE })
    }

    /**
     * 상세 차트용 — 최근 3거래일 1분봉, 시간 오름차순.
     *
     * 오늘 봉은 토스, 지난 날은 날짜 단위 보관소에서 먼저 찾고 없으면 키움 ka10080에서 받는다.
     * 개장일 달력이 모르는 오래된 날짜는 키움 페이지를 이어 붙인다.
     */
    fun minuteCandles(stockCode: String, on: LocalDate): List<MinuteCandle> {
        val today = today()
        val days = sortedMapOf<LocalDate, List<MinuteCandle>>()
        if (on == today) todayMinuteCandles(stockCode).takeIf { it.isNotEmpty() }?.let { days[today] = it }
        // 오늘이면 어제부터, 지난 날이면 그날부터(그날이 거래일이면 포함) 거슬러 간다
        var cursor = if (on == today) on else on.plusDays(1)
        while (days.size < CHART_SESSION_DAYS) {
            val day = calendar.previousOpenDay(cursor) ?: return minuteCandlesByPages(stockCode, on)
            val bars = pastDayMinutes(stockCode, day)
            if (bars.isEmpty()) break // 그날 봉을 못 받았다 — 있는 만큼만 보여준다
            days[day] = bars
            cursor = day
        }
        return days.values.flatten()
    }

    /** 일봉 차트용 — 기준일 이전 200거래일. */
    fun dailyCandles(stockCode: String, on: LocalDate): List<DailyCandle> = kiwoom.fetchDailyCandles(stockCode, 200, on)

    private fun tradingValuePool(): List<LeadingStockSnapshot> = pool.get(Unit, ::loadPool)

    /** 오늘 +30%를 넘은 적이 있는 종목(상장 첫날)에는 여기서 표시를 붙인다 — 순위가 그 표시를 본다. */
    private fun loadPool(): List<LeadingStockSnapshot> = wideLimitDays.mark(kiwoom.fetchTopTradingValueStocks(POOL_SIZE), poolTradeDate(), now())

    /**
     * 받은 순위가 어느 거래일 값인가 — 장이 열리기 전(08시 전)·휴장일엔 직전 거래일이다.
     *
     * 오늘 날짜를 붙이면 자정 넘어 받은 상장 첫날 값이 다음 거래일 내내 상장 첫날로 남는다.
     * 거래일 목록을 못 받았으면 오늘로 둔다 — 날짜를 지어내지 않는다.
     */
    private fun poolTradeDate(): LocalDate {
        val (holiday, _) = calendar.marketStatus()
        val at = now()
        if (!holiday && !at.toLocalTime().isBefore(SESSION_START)) return at.toLocalDate()
        return calendar.previousOpenDay(at.toLocalDate()) ?: at.toLocalDate()
    }

    /** 지난 거래일 하루치. 보관소에 없으면 그날을 기준일로 키움 페이지를 받고, 완성된 날들을 넣어 둔다. */
    private fun pastDayMinutes(stockCode: String, day: LocalDate): List<MinuteCandle> {
        archive.get(stockCode, day)?.let { return it }
        val page = kiwoom.fetchHistoricalMinuteCandles(stockCode, day)
        archive.putPage(stockCode, page)
        return page.filter { it.dateTime.toLocalDate() == day }.sortedBy { it.dateTime }
    }

    /**
     * 달력이 없을 때 — 키움 페이지의 **가장 이른 날**을 기준일로 이어 호출하며 거래일을 채운다.
     *
     * **기준일은 오늘보다 앞이어야 한다** — 오늘로 부르면 형성 중인 봉이 섞인다.
     */
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
            if (dayCount() == before) break // 더 과거가 없다
        }

        // 기준일 이하의 최근 거래일만 — 과거 날짜 조회 시 그 날짜까지로 한정
        val recentDays = all.map { it.dateTime.toLocalDate() }.filter { it <= on }.toSortedSet().reversed().take(CHART_SESSION_DAYS).toSet()
        val byTime = sortedMapOf<LocalDateTime, MinuteCandle>()
        all.filter { it.dateTime.toLocalDate() in recentDays }.forEach { byTime.putIfAbsent(it.dateTime, it) }
        return byTime.values.toList()
    }

    /**
     * 오늘 1분봉. 당일 저장소에서 꺼내고, 낡았으면 그 자리에서 새 봉만 이어 받는다.
     *
     * - 휴장일·08:00 전: 오늘 봉이 있을 수 없다 — 부르지 않는다.
     * - 마감 확정 뒤: 지난 날 보관소에 넘어간 확정본을 쓴다(20:01 마감 확정이 넣는다). 없으면 한 번 받아 확정본으로 넘긴다.
     * - 장중: 30초 안에 받은 값이면 그대로, 아니면 이어 받는다.
     */
    private fun todayMinuteCandles(stockCode: String): List<MinuteCandle> {
        val (holiday, _) = calendar.marketStatus()
        val at = now()
        if (holiday || at.toLocalTime().isBefore(SESSION_START)) return emptyList()
        val today = at.toLocalDate()
        archive.get(stockCode, today)?.let { return it }
        if (at.toLocalTime().isBefore(SETTLED_AFTER_CLOSE)) return todayMinutes.ensure(stockCode, at.minus(FRESH_DURING_SESSION))
        val closedAt = today.atTime(SETTLED_AFTER_CLOSE)
        val bars = todayMinutes.ensure(stockCode, closedAt)
        // 마감이 굳은 뒤에 받는 데 **성공했을 때만** 확정본이다 — 실패해 돌려받은 옛 봉은 넘기지 않는다
        if (todayMinutes.syncedSince(stockCode, closedAt)) archive.put(stockCode, today, bars)
        return bars
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = now().toLocalDate()

    companion object {
        /** 후보 풀의 원천 — 거래대금 상위 몇 개를 받는가. */
        private const val POOL_SIZE = 50

        /** 장중 수명. 갱신 폴러(15초)가 한 번 늦어도 비지 않게 여유를 둔다. */
        private val POOL_TTL = Duration.ofSeconds(20)

        /** 장이 다시 도는 시각 — 시장 달력의 거래 시간 시작(NXT 프리마켓)과 같다. */
        private val SESSION_START = LocalTime.of(8, 0)

        /** 감시 풀 등락률 하한(%). 시그널·저항지지·당일 분봉이 같은 풀을 본다. */
        const val WATCH_MIN_CHANGE_RATE = -12.0

        private const val RVOL_LOOKBACK_DAYS = 20

        /** 스파이크의 직전 평균을 내는 봉 수 */
        private const val SPIKE_BASELINE_BARS = 20

        /** 스파이크로 보는 최신 1분봉 최소 거래대금(원) */
        private const val SPIKE_MIN_TRADING_VALUE = 1_000_000_000L

        /** 지난 날 분봉 보관 기간. 거래일로 열흘 남짓 — 3거래일 차트와 달력 20일에 넉넉하다. */
        private const val MINUTE_ARCHIVE_RETENTION_DAYS = 14L

        /** 상세 차트 표시 거래일 수(당일 포함). */
        private const val CHART_SESSION_DAYS = 3

        /** 장중에 읽는 오늘 분봉은 이보다 오래되지 않아야 한다. 감시 풀은 20초마다 채워져 대개 걸리지 않는다. */
        private val FRESH_DURING_SESSION = Duration.ofSeconds(30)

        /** 마감(20:00) 뒤 이 시각부터 받은 오늘 봉은 확정본이다 — 마지막 봉이 굳을 1분을 둔다. */
        private val SETTLED_AFTER_CLOSE = LocalTime.of(20, 1)
    }
}
