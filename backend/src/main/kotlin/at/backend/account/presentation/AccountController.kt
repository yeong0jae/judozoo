package at.backend.account.presentation

import at.backend.account.application.AccountService
import at.backend.library.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(private val accountService: AccountService) {

    @GetMapping("/api/account/balance")
    fun getBalance() = ApiResponse.ok(accountService.getBalance())
}
