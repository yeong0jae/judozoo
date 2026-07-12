package at.backend.watchlist.presentation

import at.backend.library.web.ApiResponse
import at.backend.watchlist.application.StockQuote
import at.backend.watchlist.application.WatchThemeQuoteService
import at.backend.watchlist.application.WatchThemeService
import at.backend.watchlist.application.WatchThemeView
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 관심 테마 — 사용자가 직접 만드는 테마·종목 목록. (시황분석 좌측 패널) */
@RestController
class WatchThemeController(
    private val service: WatchThemeService,
    private val quoteService: WatchThemeQuoteService,
) {

    @GetMapping("/api/watch-themes")
    fun findAll(): ApiResponse<List<WatchThemeView>> = ApiResponse.ok(service.findAll())

    @PostMapping("/api/watch-themes")
    fun createTheme(@RequestBody request: CreateThemeRequest): ApiResponse<WatchThemeView> =
        ApiResponse.ok(service.createTheme(request.name))

    @DeleteMapping("/api/watch-themes/{themeId}")
    fun deleteTheme(@PathVariable themeId: Long): ApiResponse<Unit> {
        service.deleteTheme(themeId)
        return ApiResponse.ok(Unit)
    }

    @PostMapping("/api/watch-themes/{themeId}/stocks")
    fun addStock(
        @PathVariable themeId: Long,
        @RequestBody request: AddStockRequest,
    ): ApiResponse<WatchThemeView> =
        ApiResponse.ok(service.addStock(themeId, request.stockCode, request.stockName))

    @DeleteMapping("/api/watch-themes/{themeId}/stocks/{stockCode}")
    fun removeStock(
        @PathVariable themeId: Long,
        @PathVariable stockCode: String,
    ): ApiResponse<WatchThemeView> = ApiResponse.ok(service.removeStock(themeId, stockCode))

    /** 선택한 테마의 종목 시세 — codes=005930,000660 */
    @GetMapping("/api/watch-themes/quotes")
    fun quotes(@RequestParam codes: List<String>): ApiResponse<List<StockQuote>> =
        ApiResponse.ok(quoteService.quotes(codes))

    data class CreateThemeRequest(val name: String)

    data class AddStockRequest(val stockCode: String, val stockName: String)
}
