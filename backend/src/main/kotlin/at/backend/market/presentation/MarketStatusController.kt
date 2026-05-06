package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketStatusService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MarketStatusController(private val marketStatusService: MarketStatusService) {

    @GetMapping("/api/market/status")
    fun getStatus() = ApiResponse.ok(marketStatusService.getStatus())
}
