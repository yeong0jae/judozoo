package at.backend.stock.application

import at.backend.platform.kis.client.KisRestClient
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class StockService(private val kisRestClient: KisRestClient) {

    fun search(query: String): List<StockSearchResult> =
        kisRestClient.searchStock(query).output.map {
            StockSearchResult(stockCode = it.pdno, stockName = it.prdtAbrvName)
        }

    fun getPrice(stockCode: String): StockPriceResult {
        val price = kisRestClient.getCurrentPrice(stockCode).output.stckPrpr.toLong()
        return StockPriceResult(stockCode = stockCode, currentPrice = price, asOf = LocalDateTime.now())
    }

    data class StockSearchResult(val stockCode: String, val stockName: String)
    data class StockPriceResult(val stockCode: String, val currentPrice: Long, val asOf: LocalDateTime)
}
