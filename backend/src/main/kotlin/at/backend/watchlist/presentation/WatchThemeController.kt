package at.backend.watchlist.presentation

import at.backend.library.web.ApiResponse
import at.backend.watchlist.application.StockQuote
import at.backend.watchlist.application.WatchThemeQuoteService
import at.backend.watchlist.application.WatchThemeService
import at.backend.watchlist.application.WatchThemeView
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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

    @PatchMapping("/api/watch-themes/{themeId}")
    fun renameTheme(
        @PathVariable themeId: Long,
        @RequestBody request: RenameThemeRequest,
    ): ApiResponse<WatchThemeView> = ApiResponse.ok(service.renameTheme(themeId, request.name))

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
        ApiResponse.ok(service.addStock(themeId, request.stockCode, request.stockName, request.exchange))

    @DeleteMapping("/api/watch-themes/{themeId}/stocks/{stockCode}")
    fun removeStock(
        @PathVariable themeId: Long,
        @PathVariable stockCode: String,
    ): ApiResponse<WatchThemeView> = ApiResponse.ok(service.removeStock(themeId, stockCode))

    /** 테마 순서 변경 — 드래그앤드롭 결과 전체 순서를 받는다. */
    @PatchMapping("/api/watch-themes/order")
    fun reorderThemes(@RequestBody request: ReorderThemesRequest): ApiResponse<Unit> {
        service.reorderThemes(request.themeIds)
        return ApiResponse.ok(Unit)
    }

    /** 테마 내 종목 순서 변경. */
    @PatchMapping("/api/watch-themes/{themeId}/stocks/order")
    fun reorderStocks(
        @PathVariable themeId: Long,
        @RequestBody request: ReorderStocksRequest,
    ): ApiResponse<WatchThemeView> = ApiResponse.ok(service.reorderStocks(themeId, request.stockCodes))

    /** 선택한 테마의 종목 시세 — 국내/해외는 서버가 거래소로 갈라 조회한다. */
    @GetMapping("/api/watch-themes/{themeId}/quotes")
    fun quotes(@PathVariable themeId: Long): ApiResponse<List<StockQuote>> =
        ApiResponse.ok(quoteService.quotesOf(themeId))

    data class CreateThemeRequest(val name: String)

    data class RenameThemeRequest(val name: String)

    data class ReorderThemesRequest(val themeIds: List<Long>)

    data class ReorderStocksRequest(val stockCodes: List<String>)

    data class AddStockRequest(val stockCode: String, val stockName: String, val exchange: String? = null)
}
