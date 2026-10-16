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

/**
 * 종목 카탈로그 — 메모리 적재와 검색.
 *
 * 검색이 매번 DB를 때리지 않도록 카탈로그를 통째로 메모리에 든다. 교체는 항상 전체 교체라 참조 하나를 갈아끼운다.
 *
 * 하루 한 번 KIS 마스터 파일을 받아 테이블을 통째로 갈아끼운다([refresh]). 오늘 이미 갈아끼웠으면(재배포) 받지 않고 DB에서 읽는다.
 */
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

    /** [exchange]가 null이면 국내 종목. 해외는 NAS/NYS/AMS. */
    data class SearchResult(val stockCode: String, val stockName: String, val exchange: String?)

    /**
     * 국내·해외 카탈로그를 [today] 기준으로 갱신해 메모리에 올린다. 언제 할지는 [StockCatalogScheduler]가 정한다.
     *
     * 오늘 이미 동기화됐으면 DB에서 그대로 적재(다운로드 생략), 아니면 마스터 파일을 받아 전체 교체 후 적재한다.
     * 다운로드 실패는 fail-soft — 기동을 막지 않고 DB에 남은 직전 데이터라도 올린다.
     */
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

    /** DB에서 다시 읽는다. 실패하면 들고 있던 카탈로그를 그대로 둔다 — 기동을 막지 않는다. */
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
