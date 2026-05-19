package at.backend.stock.application

import at.backend.stock.domain.Stock
import at.backend.stock.domain.Stocks
import org.springframework.stereotype.Component

/**
 * 검색이 매 호출 DB를 때리지 않도록 종목 카탈로그를 메모리에 들고 있는 단일 보관소.
 * [StockCatalogRefresher]가 부팅/일일 갱신 시 [replace]로 통째 교체한다.
 */
@Component
class StockCatalog {

    @Volatile
    private var stocks = Stocks(emptyList())

    fun replace(list: List<Stock>) {
        stocks = Stocks(list)
    }

    fun search(query: String, limit: Int): List<Stock> = stocks.search(query, limit)

    val size: Int get() = stocks.size
}
