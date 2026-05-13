package at.backend.account.presentation

import at.backend.account.application.AccountService
import at.backend.library.exception.EntityNotFoundException
import at.backend.library.web.GlobalExceptionHandler
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
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

    context("GET /api/account/holdings") {
        test("보유 주식 조회 시 200과 종목별 보유/평가/활성사이클 여부를 반환한다") {
            every { accountService.getHoldings() } returns listOf(
                AccountService.HoldingResult(
                    stockCode = "005930",
                    stockName = "삼성전자",
                    qty = 10,
                    avgBuyPrice = 70_000L,
                    currentPrice = 71_000L,
                    evalProfit = 10_000L,
                    evalProfitRate = 0.014285,
                    hasActiveCycle = true,
                ),
            )

            mockMvc.get("/api/account/holdings").andExpect {
                status { isOk() }
                jsonPath("$.data[0].stockCode") { value("005930") }
                jsonPath("$.data[0].stockName") { value("삼성전자") }
                jsonPath("$.data[0].qty") { value(10) }
                jsonPath("$.data[0].avgBuyPrice") { value(70_000) }
                jsonPath("$.data[0].currentPrice") { value(71_000) }
                jsonPath("$.data[0].evalProfit") { value(10_000) }
                jsonPath("$.data[0].hasActiveCycle") { value(true) }
            }
        }

        test("보유 종목이 없으면 빈 배열을 반환한다") {
            every { accountService.getHoldings() } returns emptyList()

            mockMvc.get("/api/account/holdings").andExpect {
                status { isOk() }
                jsonPath("$.data.length()") { value(0) }
            }
        }
    }

    context("POST /api/account/holdings/{stockCode}/sell") {
        test("시장가 매도 발송 시 200과 매도된 수량·주문번호를 반환한다") {
            every { accountService.liquidate("005930") } returns AccountService.LiquidateResult(
                stockCode = "005930",
                qty = 7,
                orderNo = "0000777777",
                krxFwdgOrdOrgno = "00950",
            )

            mockMvc.post("/api/account/holdings/005930/sell").andExpect {
                status { isOk() }
                jsonPath("$.data.stockCode") { value("005930") }
                jsonPath("$.data.qty") { value(7) }
                jsonPath("$.data.orderNo") { value("0000777777") }
                jsonPath("$.data.krxFwdgOrdOrgno") { value("00950") }
            }
        }

        test("보유 종목이 없으면 404 NOT_FOUND") {
            every { accountService.liquidate("005930") } throws EntityNotFoundException("보유 종목이 없습니다: 005930")

            mockMvc.post("/api/account/holdings/005930/sell").andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("NOT_FOUND") }
            }
        }
    }
})
