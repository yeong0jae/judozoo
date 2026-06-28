package at.backend.stock.presentation

import at.backend.library.web.ApiResponse
import at.backend.stock.application.StockService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class StockController(private val stockService: StockService) {

    @GetMapping("/api/stocks/search")
    fun search(@RequestParam q: String) = ApiResponse.ok(stockService.search(q))
}
