package at.backend.stock.application

import org.springframework.stereotype.Service

@Service
class StockService(
    private val stockCatalog: StockCatalog,
) {

    fun search(query: String): List<StockSearchResult> =
        stockCatalog.search(query, SEARCH_LIMIT)
            .map { StockSearchResult(stockCode = it.shortCode, stockName = it.name) }

    data class StockSearchResult(val stockCode: String, val stockName: String)

    companion object {
        private const val SEARCH_LIMIT = 20
    }
}
