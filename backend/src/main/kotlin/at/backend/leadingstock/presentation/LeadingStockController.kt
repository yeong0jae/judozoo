package at.backend.leadingstock.presentation

import at.backend.leadingstock.application.InvestorTrendService
import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.application.LeadingStockService
import at.backend.leadingstock.presentation.response.CandidateStockItem
import at.backend.leadingstock.presentation.response.CandidateStocksResponse
import at.backend.leadingstock.presentation.response.FilterResultItem
import at.backend.leadingstock.presentation.response.InvestorTrendDayItem
import at.backend.leadingstock.presentation.response.LeadingStockDetailResponse
import at.backend.leadingstock.presentation.response.SwingHighSignalItem
import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/leading-stocks")
class LeadingStockController(
    private val leadingStockService: LeadingStockService,
    private val investorTrendService: InvestorTrendService,
    private val criteria: LeadingStockCriteriaProperties,
    private val timeProvider: TimeProvider,
) {

    /**
     * Phase 1 후보 리스트 (거래대금 순위 + 등락률 필터만 통과).
     * minChangeRate: 당일 등락률 임계값(%). 사용자가 -7~7 중 선택, 미지정 시 설정 기본값.
     */
    @GetMapping("/candidates")
    fun getCandidateStocks(
        @RequestParam(required = false) minChangeRate: Int?,
    ): ApiResponse<CandidateStocksResponse> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble()
            ?: criteria.minDailyPriceChangeRate
        val candidates = leadingStockService.findCandidateStocks(rate)
        val items = candidates.mapIndexed { i, s ->
            val themes = leadingStockService.themesOf(s.stockCode)
            CandidateStockItem(
                rank = i + 1,
                stockCode = s.stockCode,
                stockName = s.stockName,
                currentPrice = s.currentPrice,
                priceChangeRate = s.priceChangeRate,
                accumulatedTradingValue = s.accumulatedTradingValue,
                themes = themes.take(MAX_THEME_CHIPS),
                themeCount = themes.size,
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

    /** 종목별 일자별 외국인·기관·개인 순매수 추이 (단위: 백만원). */
    @GetMapping("/candidates/{stockCode}/investors")
    fun getInvestorTrend(@PathVariable stockCode: String): ApiResponse<List<InvestorTrendDayItem>> {
        val trend = investorTrendService.getTrend(stockCode)
        return ApiResponse.ok(
            trend.map {
                InvestorTrendDayItem(
                    date = it.date,
                    individualNet = it.individualNet,
                    foreignNet = it.foreignNet,
                    institutionNet = it.institutionNet,
                    individualNetNxt = it.individualNetNxt,
                    foreignNetNxt = it.foreignNetNxt,
                    institutionNetNxt = it.institutionNetNxt,
                )
            },
        )
    }

    /** 종목 상세 — 전체 필터(A~H) 평가 결과 + 상대거래량 */
    @GetMapping("/candidates/{stockCode}")
    fun getStockDetail(@PathVariable stockCode: String): ApiResponse<LeadingStockDetailResponse> {
        val eval = leadingStockService.evaluateStock(stockCode)
        val stock = eval.stock
        return ApiResponse.ok(
            LeadingStockDetailResponse(
                stockCode = stock.stockCode,
                stockName = stock.stockName,
                currentPrice = stock.currentPrice,
                priceChangeRate = stock.priceChangeRate,
                relativeVolume = eval.relativeVolume,
                themes = leadingStockService.themesOf(stock.stockCode),
                swingHighSignal = eval.swingHighSignal?.let {
                    SwingHighSignalItem(
                        peakPrice = it.peakPrice,
                        peakAt = it.peakAt,
                        gapRate = it.gapRate,
                    )
                },
                filterResults = eval.filterResults.map {
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

    companion object {
        private const val MIN_CHANGE_RATE = -7
        private const val MAX_CHANGE_RATE = 7
        private const val MAX_THEME_CHIPS = 2
    }
}
