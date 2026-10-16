package judozoo.stock.presentation

import judozoo.library.web.ApiResponse
import judozoo.stock.application.StockCatalog
import judozoo.stock.application.StockInvestorService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/stocks")
class StockController(private val catalog: StockCatalog, private val investors: StockInvestorService) {
    @GetMapping("/search")
    fun search(@RequestParam q: String): ApiResponse<List<StockCatalog.SearchResult>> = ApiResponse.ok(catalog.search(q))

    @GetMapping("/{stockCode}/investor/daily")
    fun investorDaily(@PathVariable stockCode: String, @RequestParam(defaultValue = "10") count: Int) =
        ApiResponse.ok(investors.investorDailyHistory(stockCode, count))
}
