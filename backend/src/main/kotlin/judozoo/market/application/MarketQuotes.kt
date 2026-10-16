package judozoo.market.application

import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.domain.FuturesExpiry
import judozoo.market.domain.FuturesSession
import judozoo.market.domain.Indicator
import judozoo.market.domain.QuoteHours
import judozoo.platform.kis.KisFutures
import judozoo.platform.kiwoom.KiwoomIndex
import judozoo.platform.yahoo.YahooClient
import judozoo.stock.domain.Market
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

@Service
class MarketQuotes(
    private val kiwoomIndex: KiwoomIndex,
    private val futures: KisFutures,
    private val contracts: FuturesContracts,
    private val yahoo: YahooClient,
    private val clock: Clock,
) {
    data class IndexResult(val currentValue: Double, val changeRate: Double)

    data class FuturesQuote(
        val futuresPrice: Double,
        val changeRate: Double,
        val spot: Double,
        val basis: Double,
        val dprt: Double,
        val openInterest: Long,
        val openInterestChange: Long,
        val rmnnDays: Int,
        val expiryDate: String,
    )

    data class NightFuturesQuote(
        val price: Double,
        val changeRate: Double,
        val dayClose: Double,
        val gap: Double,
        val open: Double,
        val high: Double,
        val low: Double,
        val volume: Long,
        val openInterest: Long,
        val openInterestChange: Long,
    )

    data class QuoteResult(val price: Double, val prevClose: Double, val priceChange: Double, val changeRate: Double)

    data class MacroQuotes(val usdKrw: QuoteResult?, val wti: QuoteResult?, val vix: QuoteResult?, val us10y: QuoteResult?)

    private val indexes = TtlCache<Market, IndexResult>("index", Duration.ofSeconds(40), maxSize = 2, serveStale = true)
    private val futuresQuotes = TtlCache<Market, FuturesQuote?>("futuresQuote", Duration.ofSeconds(40), maxSize = 2, skipIf = { it == null }, serveStale = true)
    private val nightQuotes = TtlCache<Unit, NightFuturesQuote?>("nightFuturesQuote", Duration.ofSeconds(40), maxSize = 1, skipIf = { it == null }, serveStale = true)
    private val yahooQuotes = TtlCache<Indicator, QuoteResult?>("yahooQuotes", Duration.ofSeconds(40), maxSize = 6, skipIf = { it == null }, serveStale = true)

    fun index(market: Market): IndexResult = indexes.get(market) { loadIndex(market) }

    fun futuresQuote(market: Market): FuturesQuote? = futuresQuotes.get(market) { loadFuturesQuote(market) }

    fun nightFuturesQuote(): NightFuturesQuote? = nightQuotes.get(Unit) { loadNightQuote() }

    fun nasdaqQuote(): QuoteResult? = quoteOf(Indicator.NASDAQ)

    fun nasdaqFuturesQuote(): QuoteResult? = quoteOf(Indicator.NASDAQ_FUTURES)

    fun macroQuotes() = MacroQuotes(quoteOf(Indicator.USD_KRW), quoteOf(Indicator.WTI), quoteOf(Indicator.VIX), quoteOf(Indicator.US10Y))

    fun quoteOf(indicator: Indicator): QuoteResult? = yahooQuotes.get(indicator) { loadQuote(indicator) }

    class Refresh(val name: String, val hours: QuoteHours, private val held: () -> Any?, private val refresh: () -> Any?) {
        fun run(active: () -> Boolean): Boolean = if (held() == null || active()) refresh() != null else true
    }

    fun refreshers(): List<Refresh> = Market.entries.map { m ->
        Refresh(m.name, QuoteHours.DOMESTIC_INDEX, { indexes.peek(m) }) { indexes.refresh(m) { loadIndex(m) } }
    } + Market.entries.map { m ->
        Refresh("futures-${m.name}", QuoteHours.DOMESTIC_FUTURES, { futuresQuotes.peek(m) }) { futuresQuotes.refresh(m) { loadFuturesQuote(m) } }
    } + Refresh("night", QuoteHours.NIGHT_FUTURES, { nightQuotes.peek(Unit) }) { nightQuotes.refresh(Unit) { loadNightQuote() } } +
        Indicator.entries.map { i -> Refresh(i.symbol, i.hours, { yahooQuotes.peek(i) }) { yahooQuotes.refresh(i) { loadQuote(i) } } }

    private fun loadIndex(market: Market): IndexResult {
        val snapshot = kiwoomIndex.fetchIndex(market) ?: throw IllegalStateException("${market.name} 지수 조회 실패")
        return IndexResult(snapshot.currentValue, snapshot.changeRate)
    }

    private fun loadFuturesQuote(market: Market): FuturesQuote? {
        var near = contracts.nearMonth(market) ?: return null
        val today = LocalDate.now(clock.withZone(KST))
        val daily = futures.fetchDaily(near.iscd, today.minusDays(10), today)
            ?: contracts.changed(market, near.iscd)?.let { near = it; futures.fetchDaily(it.iscd, today.minusDays(10), today) }
            ?: return null
        val s = daily.summary
        return FuturesQuote(
            s.futuresPrice, s.changeRate, s.spot, s.basis, s.dprt, s.openInterest, s.openInterestChange,
            near.remainingDays, FuturesExpiry.of(near.name)?.toString().orEmpty(),
        )
    }

    private fun loadNightQuote(): NightFuturesQuote? {
        val near = contracts.nearMonth(Market.KOSPI) ?: return null
        val p = futures.fetchPrice(near.iscd, FuturesSession.NIGHT)
            ?: contracts.changed(Market.KOSPI, near.iscd)?.let { futures.fetchPrice(it.iscd, FuturesSession.NIGHT) }
            ?: return null
        return NightFuturesQuote(p.price, p.changeRate, p.prevClose, p.priceChange, p.open, p.high, p.low, p.volume, p.openInterest, p.openInterestChange)
    }

    private fun loadQuote(indicator: Indicator): QuoteResult? {
        val q = yahoo.fetchQuote(indicator.symbol) ?: return null
        val change = q.price - q.prevClose
        return QuoteResult(q.price, q.prevClose, change, if (q.prevClose == 0.0) 0.0 else change / q.prevClose * 100)
    }
}
