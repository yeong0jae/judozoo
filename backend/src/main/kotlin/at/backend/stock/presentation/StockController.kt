package at.backend.stock.presentation

import at.backend.library.web.ApiResponse
import at.backend.stock.application.StockInvestorDay
import at.backend.stock.application.StockInvestorService
import at.backend.stock.application.StockService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class StockController(
    private val stockService: StockService,
    private val investorService: StockInvestorService,
) {

    @GetMapping("/api/stocks/search")
    fun search(@RequestParam q: String) = ApiResponse.ok(stockService.search(q))

    /** 최근 N거래일 종목 투자자 순매수(억원) — 시장 수급 표와 같은 구성. (시황분석 종목 상세용) */
    @GetMapping("/api/stocks/{stockCode}/investor/daily")
    fun investorDaily(
        @PathVariable stockCode: String,
        @RequestParam(defaultValue = "10") count: Int,
    ): ApiResponse<List<StockInvestorDay>> =
        ApiResponse.ok(investorService.dailyHistory(stockCode, count))
}
