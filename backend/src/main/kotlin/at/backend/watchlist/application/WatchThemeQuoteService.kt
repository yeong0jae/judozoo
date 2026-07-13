package at.backend.watchlist.application

import at.backend.platform.kiwoom.client.KiwoomMarketClient
import at.backend.platform.yahoo.client.YahooChartClient
import at.backend.watchlist.infrastructure.repository.WatchThemeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관심 종목 시세 — 국내는 키움(ka10001), 해외는 야후(KIS 해외 실시간은 유료시세라 쓰지 않는다).
 * 화면에 보이는 테마의 종목만 조회한다. 전체 테마를 매번 부르면 키움 rate limit에 걸린다.
 */
@Service
class WatchThemeQuoteService(
    private val kiwoom: KiwoomMarketClient,
    private val yahoo: YahooChartClient,
    private val repository: WatchThemeRepository,
) {

    @Transactional(readOnly = true)
    fun quotesOf(themeId: Long): List<StockQuote> {
        val theme = repository.findById(themeId).orElse(null) ?: return emptyList()
        return theme.stocks.mapNotNull { stock ->
            if (stock.exchange == null) domestic(stock.stockCode) else overseas(stock.stockCode)
        }
    }

    private fun domestic(code: String): StockQuote? =
        kiwoom.fetchStockDetail(code)?.let {
            StockQuote(
                stockCode = code,
                stockName = it.stockName,
                currentPrice = it.currentPrice.toDouble(),
                priceChangeRate = it.priceChangeRate,
                overseas = false,
            )
        }

    private fun overseas(symbol: String): StockQuote? =
        yahoo.fetchExtendedQuote(symbol)?.let {
            val change = it.price - it.prevClose
            StockQuote(
                stockCode = symbol,
                stockName = it.name,
                currentPrice = it.price,
                priceChangeRate = if (it.prevClose == 0.0) 0.0 else change / it.prevClose * 100,
                overseas = true,
            )
        }
}

/** 종목 시세 — 관심 테마 리스트 표시용. 해외는 달러, 국내는 원. */
data class StockQuote(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Double,
    val priceChangeRate: Double,
    val overseas: Boolean,
)
