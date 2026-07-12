package at.backend.stock.application

import org.springframework.stereotype.Service

/** 종목 검색 — 국내(코스피/코스닥) + 해외(NAS/NYS/AMS). 관심 테마에 담을 종목을 고르는 데 쓴다. */
@Service
class StockService(
    private val stockCatalog: StockCatalog,
    private val overseasCatalog: OverseasStockCatalog,
) {

    fun search(query: String): List<StockSearchResult> {
        val domestic = stockCatalog.search(query, SEARCH_LIMIT)
            .map { StockSearchResult(stockCode = it.shortCode, stockName = it.name, exchange = null) }
        val overseas = overseasCatalog.search(query, SEARCH_LIMIT)
            .map { StockSearchResult(stockCode = it.symbol, stockName = it.name, exchange = it.exchange) }
        return (domestic + overseas).take(SEARCH_LIMIT)
    }

    /** [exchange]가 null이면 국내 종목. 해외는 NAS/NYS/AMS. */
    data class StockSearchResult(val stockCode: String, val stockName: String, val exchange: String?)

    companion object {
        private const val SEARCH_LIMIT = 20
    }
}
