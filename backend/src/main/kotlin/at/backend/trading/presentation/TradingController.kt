package at.backend.trading.presentation

import at.backend.trading.application.TradingQueryService
import at.backend.trading.application.TradingService
import at.backend.trading.presentation.request.CreateTradingRequest
import at.backend.library.web.ApiResponse
import at.backend.trading.TradingProperties
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class TradingController(
    private val tradingService: TradingService,
    private val tradingQueryService: TradingQueryService,
    private val tradingProperties: TradingProperties,
) {

    @PostMapping("/api/trading")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateTradingRequest) =
        ApiResponse.created(tradingService.create(request.toTradingInput(tradingProperties)))

    @DeleteMapping("/api/trading/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun cancel(@PathVariable id: Long) =
        ApiResponse.accepted(tradingService.cancel(id))

    @GetMapping("/api/trading")
    fun list(@RequestParam status: String) = when (status) {
        "today" -> ApiResponse.ok(tradingQueryService.findToday())
        else -> ApiResponse.ok(tradingQueryService.findActive())
    }

    @GetMapping("/api/trading/{id}")
    fun findById(@PathVariable id: Long) =
        ApiResponse.ok(tradingQueryService.findById(id))
}
