package judozoo.market.application

import judozoo.library.KST
import judozoo.library.TtlCache
import judozoo.market.domain.FuturesExpiry
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
    private val yahooQuotes = TtlCache<String, QuoteResult?>("yahooQuotes", Duration.ofSeconds(40), maxSize = 6, skipIf = { it == null }, serveStale = true)

    fun index(market: Market): IndexResult = indexes.get(market) { loadIndex(market) }

    fun futuresQuote(market: Market): FuturesQuote? = futuresQuotes.get(market) { loadFuturesQuote(market) }

    fun nightFuturesQuote(): NightFuturesQuote? = nightQuotes.get(Unit) { loadNightQuote() }

    fun nasdaqQuote(): QuoteResult? = quoteOf(NASDAQ_SYMBOL)

    fun nasdaqFuturesQuote(): QuoteResult? = quoteOf(NASDAQ_FUTURES_SYMBOL)

    fun macroQuotes() = MacroQuotes(
        quoteOf(MACRO_SYMBOLS.getValue("USD_KRW")), quoteOf(MACRO_SYMBOLS.getValue("WTI")),
        quoteOf(MACRO_SYMBOLS.getValue("VIX")), quoteOf(MACRO_SYMBOLS.getValue("US10Y")),
    )

    fun quoteOf(symbol: String): QuoteResult? = yahooQuotes.get(symbol) { loadQuote(symbol) }

    class Refresh(val name: String, private val held: () -> Any?, private val refresh: () -> Any?) {
        fun run(active: () -> Boolean): Boolean = if (held() == null || active()) refresh() != null else true
    }

    fun refreshers(): Map<String, Refresh> = linkedMapOf(
        "kospi" to Refresh("kospi", { indexes.peek(Market.KOSPI) }) { indexes.refresh(Market.KOSPI) { loadIndex(Market.KOSPI) } },
        "kosdaq" to Refresh("kosdaq", { indexes.peek(Market.KOSDAQ) }) { indexes.refresh(Market.KOSDAQ) { loadIndex(Market.KOSDAQ) } },
        "futures-kospi" to Refresh("futures-kospi", { futuresQuotes.peek(Market.KOSPI) }) { futuresQuotes.refresh(Market.KOSPI) { loadFuturesQuote(Market.KOSPI) } },
        "futures-kosdaq" to Refresh("futures-kosdaq", { futuresQuotes.peek(Market.KOSDAQ) }) { futuresQuotes.refresh(Market.KOSDAQ) { loadFuturesQuote(Market.KOSDAQ) } },
        "night" to Refresh("night", { nightQuotes.peek(Unit) }) { nightQuotes.refresh(Unit) { loadNightQuote() } },
    ).apply {
        (listOf(NASDAQ_SYMBOL, NASDAQ_FUTURES_SYMBOL) + MACRO_SYMBOLS.values).forEach { symbol ->
            put(symbol, Refresh(symbol, { yahooQuotes.peek(symbol) }) { yahooQuotes.refresh(symbol) { loadQuote(symbol) } })
        }
    }

    private fun loadIndex(market: Market): IndexResult {
        val (code, type) = INDEX_CODE.getValue(market)
        val snapshot = kiwoomIndex.fetchIndex(code, type) ?: throw IllegalStateException("${market.name} 지수 조회 실패")
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
        val p = futures.fetchPrice(near.iscd, KisFutures.NIGHT)
            ?: contracts.changed(Market.KOSPI, near.iscd)?.let { futures.fetchPrice(it.iscd, KisFutures.NIGHT) }
            ?: return null
        return NightFuturesQuote(p.price, p.changeRate, p.prevClose, p.priceChange, p.open, p.high, p.low, p.volume, p.openInterest, p.openInterestChange)
    }

    private fun loadQuote(symbol: String): QuoteResult? {
        val q = yahoo.fetchQuote(symbol) ?: return null
        val change = q.price - q.prevClose
        return QuoteResult(q.price, q.prevClose, change, if (q.prevClose == 0.0) 0.0 else change / q.prevClose * 100)
    }

    companion object {
        private val INDEX_CODE = mapOf(Market.KOSPI to ("001" to "0"), Market.KOSDAQ to ("101" to "1"))

        const val NASDAQ_SYMBOL = "^IXIC"

        const val NASDAQ_FUTURES_SYMBOL = "NQ=F"

        val MACRO_SYMBOLS = linkedMapOf("USD_KRW" to "KRW=X", "WTI" to "CL=F", "VIX" to "^VIX", "US10Y" to "^TNX")
    }
}
