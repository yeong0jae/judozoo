package at.backend.stock.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisRestClient
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class StockService(
    private val kisRestClient: KisRestClient,
    private val timeProvider: TimeProvider,
) {

    fun search(query: String): List<StockSearchResult> {
        val output = kisRestClient.searchStock(query).output
        return listOf(StockSearchResult(stockCode = output.pdno, stockName = output.prdtAbrvName))
    }

    fun getPrice(stockCode: String): StockPriceResult {
        val price = kisRestClient.getCurrentPrice(stockCode).output.stckPrpr.toLong()
        return StockPriceResult(stockCode = stockCode, currentPrice = price, asOf = timeProvider.now())
    }

    data class StockSearchResult(val stockCode: String, val stockName: String)
    data class StockPriceResult(val stockCode: String, val currentPrice: Long, val asOf: LocalDateTime)
}
