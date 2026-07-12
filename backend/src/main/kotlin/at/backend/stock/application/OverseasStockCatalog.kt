package at.backend.stock.application

import at.backend.stock.domain.OverseasStock
import org.springframework.stereotype.Component

/** 해외 종목 카탈로그 — 국내와 같은 방식으로 메모리에 들고 검색한다. */
@Component
class OverseasStockCatalog {

    @Volatile
    private var stocks: List<OverseasStock> = emptyList()

    fun replace(list: List<OverseasStock>) {
        stocks = list
    }

    fun search(query: String, limit: Int): List<OverseasStock> =
        stocks.asSequence().filter { it.matches(query) }.take(limit).toList()

    val size: Int get() = stocks.size
}
