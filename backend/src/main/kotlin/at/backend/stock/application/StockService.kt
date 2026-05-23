package at.backend.stock.application

import at.backend.library.time.TimeProvider
import at.backend.trading.application.broker.BrokerTradingClient
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class StockService(
    private val broker: BrokerTradingClient,
    private val stockCatalog: StockCatalog,
    private val timeProvider: TimeProvider,
) {

    fun search(query: String): List<StockSearchResult> =
        stockCatalog.search(query, SEARCH_LIMIT)
            .map { StockSearchResult(stockCode = it.shortCode, stockName = it.name) }

    fun getPrice(stockCode: String): StockPriceResult {
        val price = broker.currentPrice(stockCode)
        return StockPriceResult(stockCode = stockCode, currentPrice = price, asOf = timeProvider.now())
    }

    data class StockSearchResult(val stockCode: String, val stockName: String)
    data class StockPriceResult(val stockCode: String, val currentPrice: Long, val asOf: LocalDateTime)

    companion object {
        private const val SEARCH_LIMIT = 20
    }
}
