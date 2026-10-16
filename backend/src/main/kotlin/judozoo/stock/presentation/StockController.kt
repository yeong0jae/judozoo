package judozoo.stock.presentation

import judozoo.library.web.ApiResponse
import judozoo.stock.application.StockCatalog
import judozoo.stock.application.StockInvestorService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 종목 검색 · 종목 투자자 수급 API. 둘 다 로그인 뒤다(공개 목록에 없다). */
@RestController
@RequestMapping("/api/stocks")
class StockController(private val catalog: StockCatalog, private val investors: StockInvestorService) {

    /** 종목명·코드로 검색. 국내 + 해외(NAS/NYS/AMS) 통합, 최대 20건. */
    @GetMapping("/search")
    fun search(@RequestParam q: String): ApiResponse<List<StockCatalog.SearchResult>> = ApiResponse.ok(catalog.search(q))

    /** 최근 N거래일 종목 투자자 순매수(백만원) — 시황분석 종목 상세용. */
    @GetMapping("/{stockCode}/investor/daily")
    fun investorDaily(@PathVariable stockCode: String, @RequestParam(defaultValue = "10") count: Int) =
        ApiResponse.ok(investors.investorDailyHistory(stockCode, count))
}
