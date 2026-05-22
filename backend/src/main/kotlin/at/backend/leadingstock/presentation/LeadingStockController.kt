package at.backend.leadingstock.presentation

import at.backend.leadingstock.application.LeadingStockService
import at.backend.leadingstock.presentation.response.CandidateStockItem
import at.backend.leadingstock.presentation.response.CandidateStocksResponse
import at.backend.leadingstock.presentation.response.FilterResultItem
import at.backend.leadingstock.presentation.response.LeadingStockDetailResponse
import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/leading-stocks")
class LeadingStockController(
    private val leadingStockService: LeadingStockService,
    private val timeProvider: TimeProvider,
) {

    /** Phase 1 후보 리스트 (거래대금 순위 + 등락률 필터만 통과) */
    @GetMapping("/candidates")
    fun getCandidateStocks(): ApiResponse<CandidateStocksResponse> {
        val candidates = leadingStockService.findCandidateStocks()
        val items = candidates.mapIndexed { i, s ->
            CandidateStockItem(
                rank = i + 1,
                stockCode = s.stockCode,
                stockName = s.stockName,
                currentPrice = s.currentPrice,
                priceChangeRate = s.priceChangeRate,
                accumulatedTradingValue = s.accumulatedTradingValue,
            )
        }
        return ApiResponse.ok(
            CandidateStocksResponse(
                queriedAt = timeProvider.now(),
                totalCount = items.size,
                stocks = items,
            ),
        )
    }

    /** 종목 상세 — 전체 필터(A~H) 평가 결과 */
    @GetMapping("/candidates/{stockCode}")
    fun getStockDetail(@PathVariable stockCode: String): ApiResponse<LeadingStockDetailResponse> {
        val (stock, filterResults) = leadingStockService.evaluateStock(stockCode)
        return ApiResponse.ok(
            LeadingStockDetailResponse(
                stockCode = stock.stockCode,
                stockName = stock.stockName,
                currentPrice = stock.currentPrice,
                priceChangeRate = stock.priceChangeRate,
                filterResults = filterResults.map {
                    FilterResultItem(
                        filterName = it.filterName,
                        criteriaDescription = it.criteriaDescription,
                        actualValue = it.actualValue,
                        passed = it.passed,
                    )
                },
            ),
        )
    }
}
