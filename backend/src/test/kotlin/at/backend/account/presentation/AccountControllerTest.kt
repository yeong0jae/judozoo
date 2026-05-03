package at.backend.account.presentation

import at.backend.account.application.AccountService
import at.backend.library.web.GlobalExceptionHandler
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AccountControllerTest : FunSpec({

    val accountService = mockk<AccountService>()
    val mockMvc = MockMvcBuilders
        .standaloneSetup(AccountController(accountService))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    context("GET /api/account/balance") {
        test("잔고 조회 시 200과 cashBalance/reservedAmount/availableBalance를 반환한다") {
            every { accountService.getBalance() } returns AccountService.AccountBalanceResult(
                cashBalance = 1_000_000L,
                reservedAmount = 300_000L,
                availableBalance = 700_000L,
            )

            mockMvc.get("/api/account/balance").andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.status") { value(200) }
                jsonPath("$.data.cashBalance") { value(1_000_000) }
                jsonPath("$.data.reservedAmount") { value(300_000) }
                jsonPath("$.data.availableBalance") { value(700_000) }
            }
        }
    }
})
