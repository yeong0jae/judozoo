package at.backend.account.presentation

import at.backend.account.application.AccountService
import at.backend.library.web.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(private val accountService: AccountService) {

    private val log = KotlinLogging.logger {}

    @GetMapping("/api/account/balance")
    fun getBalance(@RequestHeader(value = "Referer", required = false) referer: String?): ApiResponse<AccountService.AccountBalanceResult> {
        log.info { "GET /api/account/balance referer=$referer" }
        return ApiResponse.ok(accountService.getBalance())
    }

    @GetMapping("/api/account/holdings")
    fun getHoldings(): ApiResponse<List<AccountService.HoldingResult>> {
        log.info { "GET /api/account/holdings" }
        return ApiResponse.ok(accountService.getHoldings())
    }

    @PostMapping("/api/account/holdings/{stockCode}/sell")
    fun sellHolding(@PathVariable stockCode: String): ApiResponse<AccountService.LiquidateResult> {
        log.info { "POST /api/account/holdings/$stockCode/sell" }
        return ApiResponse.ok(accountService.liquidate(stockCode))
    }
}
