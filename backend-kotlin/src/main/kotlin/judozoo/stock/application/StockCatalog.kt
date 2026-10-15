package judozoo.stock.application

import judozoo.stock.domain.Market
import judozoo.stock.domain.OverseasStock
import judozoo.stock.domain.OverseasStockRepository
import judozoo.stock.domain.StockRepository
import judozoo.stock.domain.Stocks
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 종목 카탈로그 — 메모리 적재와 검색.
 *
 * 검색이 매번 DB를 때리지 않도록 카탈로그를 통째로 메모리에 든다. 교체는 항상 전체 교체라 참조 하나를 갈아끼운다.
 *
 * **마스터 파일을 받아 테이블을 갈아끼우는 일은 Python이 한다(07:40)** — 시장·주도주가 아직 Python에서 이 카탈로그를 쓰고,
 * 두 쪽이 같은 테이블을 지웠다 넣으면 서로를 덮는다. 여기서는 DB에서 읽기만 한다.
 */
@Service
class StockCatalog(
    private val stocks: StockRepository,
    private val overseasStocks: OverseasStockRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var domestic = Stocks.EMPTY
    @Volatile private var overseas: List<OverseasStock> = emptyList()

    /** [exchange]가 null이면 국내 종목. 해외는 NAS/NYS/AMS. */
    data class SearchResult(val stockCode: String, val stockName: String, val exchange: String?)

    /** DB에서 다시 읽는다. 실패하면 들고 있던 카탈로그를 그대로 둔다 — 기동을 막지 않는다. 언제 읽을지는 [StockCatalogScheduler]가 정한다. */
    fun reload() {
        try {
            domestic = Stocks(stocks.findAll())
            overseas = overseasStocks.findAll()
            log.info("종목 카탈로그 적재 — 국내 {}건, 해외 {}건", domestic.size, overseas.size)
        } catch (e: Exception) {
            log.warn("종목 카탈로그 적재 실패 — 들고 있던 것을 그대로 쓴다", e)
        }
    }

    /** 국내(코스피/코스닥) + 해외(NAS/NYS/AMS) 통합 검색. 국내가 앞, 최대 [SEARCH_LIMIT]건. */
    fun search(query: String): List<SearchResult> {
        val found = domestic.search(query, SEARCH_LIMIT).map { SearchResult(it.shortCode, it.name, null) }
        val q = query.trim().lowercase()
        val abroad = if (q.isEmpty()) emptyList() else overseas.filter { it.matches(q) }.take(SEARCH_LIMIT).map { SearchResult(it.symbol, it.name, it.exchange) }
        return (found + abroad).take(SEARCH_LIMIT)
    }

    /** 그 종목이 코스피인지 코스닥인지. 카탈로그에 없으면 null. 메모리에 상주하므로 조회 비용이 없다. */
    fun marketOf(stockCode: String): Market? = domestic.find(stockCode)?.market

    /** (국내, 해외) 적재 건수 — 로그·검증용. */
    fun sizes(): Pair<Int, Int> = domestic.size to overseas.size

    companion object {
        const val SEARCH_LIMIT = 20
    }
}
