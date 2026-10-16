package judozoo.stock.application

import judozoo.library.KST
import judozoo.stock.domain.Market
import judozoo.stock.domain.OverseasStock
import judozoo.stock.domain.OverseasStockRepository
import judozoo.stock.domain.StockRepository
import judozoo.stock.domain.Stocks
import judozoo.stock.infrastructure.KisStockMaster
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class StockCatalog(
    private val stocks: StockRepository,
    private val overseasStocks: OverseasStockRepository,
    private val master: KisStockMaster,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var domestic = Stocks.EMPTY
    @Volatile private var overseas: List<OverseasStock> = emptyList()

    data class SearchResult(val stockCode: String, val stockName: String, val exchange: String?)

    fun refresh(today: LocalDate) {
        val at = LocalDateTime.now(clock.withZone(KST))
        try {
            if (stocks.findFirstByOrderByCreatedAtDesc()?.createdAt?.toLocalDate() == today) {
                domestic = Stocks(stocks.findAll())
                log.info("종목 카탈로그 — 오늘 이미 동기화됨, DB에서 {}건 적재", domestic.size)
            } else {
                val fetched = master.fetchDomestic(at)
                transaction.executeWithoutResult { stocks.deleteAllInBatch(); stocks.saveAll(fetched) }
                domestic = Stocks(fetched)
                log.info("종목 카탈로그 갱신 완료 — {}건", fetched.size)
            }
        } catch (e: Exception) {
            log.warn("종목 카탈로그 갱신 실패 — DB의 직전 데이터로 폴백", e)
            runCatching { domestic = Stocks(stocks.findAll()) }.onFailure { log.warn("직전 데이터 적재도 실패", it) }
        }
        try {
            if (overseasStocks.findFirstByOrderByCreatedAtDesc()?.createdAt?.toLocalDate() == today) {
                overseas = overseasStocks.findAll()
                log.info("해외 종목 카탈로그 — 오늘 이미 동기화됨, DB에서 {}건 적재", overseas.size)
            } else {
                val fetched = master.fetchOverseas(at)
                transaction.executeWithoutResult { overseasStocks.deleteAllInBatch(); overseasStocks.saveAll(fetched) }
                overseas = fetched
                log.info("해외 종목 카탈로그 갱신 완료 — {}건", fetched.size)
            }
        } catch (e: Exception) {
            log.warn("해외 종목 카탈로그 갱신 실패 — DB의 직전 데이터로 폴백", e)
            runCatching { overseas = overseasStocks.findAll() }.onFailure { log.warn("직전 데이터 적재도 실패", it) }
        }
    }

    fun reload() {
        try {
            domestic = Stocks(stocks.findAll())
            overseas = overseasStocks.findAll()
            log.info("종목 카탈로그 적재 — 국내 {}건, 해외 {}건", domestic.size, overseas.size)
        } catch (e: Exception) {
            log.warn("종목 카탈로그 적재 실패 — 들고 있던 것을 그대로 쓴다", e)
        }
    }

    fun search(query: String): List<SearchResult> {
        val found = domestic.search(query, SEARCH_LIMIT).map { SearchResult(it.shortCode, it.name, null) }
        val q = query.trim().lowercase()
        val abroad = if (q.isEmpty()) emptyList() else overseas.filter { it.matches(q) }.take(SEARCH_LIMIT).map { SearchResult(it.symbol, it.name, it.exchange) }
        return (found + abroad).take(SEARCH_LIMIT)
    }

    fun marketOf(stockCode: String): Market? = domestic.find(stockCode)?.market

    fun sizes(): Pair<Int, Int> = domestic.size to overseas.size

    companion object {
        const val SEARCH_LIMIT = 20
    }
}
