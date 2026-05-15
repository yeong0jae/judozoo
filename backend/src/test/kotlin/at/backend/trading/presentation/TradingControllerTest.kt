package at.backend.trading.presentation

import at.backend.library.exception.EntityNotFoundException
import at.backend.library.web.GlobalExceptionHandler
import at.backend.trading.TradingProperties
import at.backend.trading.application.TradingQueryService
import at.backend.trading.application.TradingService
import at.backend.trading.application.result.TradingCancelResult
import at.backend.trading.application.result.TradingCreatedResult
import at.backend.trading.application.result.TradingDetailResult
import at.backend.trading.application.result.TradingSummaryResult
import at.backend.trading.domain.AlreadyClosedException
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDateTime

class TradingControllerTest : FunSpec({

    val tradingService = mockk<TradingService>()
    val tradingQueryService = mockk<TradingQueryService>()
    val tradingProperties = TradingProperties(
        defaultBuyIntervalMin = 3,
        defaultSplitSellRatio = BigDecimal("0.2"),
        defaultMidwayProfitPct = BigDecimal("3"),
        defaultBreakevenThresholdPct = BigDecimal("2"),
        defaultStopLossPct = BigDecimal("2"),
        sellCostRate = BigDecimal("0.0025"),
    )
    val mockMvc = MockMvcBuilders
        .standaloneSetup(TradingController(tradingService, tradingQueryService, tradingProperties))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    context("POST /api/trading") {
        test("유효한 요청이면 201과 id를 반환한다") {
            every { tradingService.create(any()) } returns TradingCreatedResult(id = 1L)

            mockMvc.post("/api/trading") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"stockCode":"005930","perBuyQty":1}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.status") { value(201) }
                jsonPath("$.data.id") { value(1) }
            }
        }

        test("stockCode가 빈 문자열이면 400 INVALID_PARAMETER") {
            mockMvc.post("/api/trading") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"stockCode":"","perBuyQty":1}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_PARAMETER") }
            }
        }

        test("perBuyQty가 없거나 0이하이면 400 INVALID_PARAMETER") {
            mockMvc.post("/api/trading") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"stockCode":"005930","perBuyQty":-1}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_PARAMETER") }
            }
        }
    }

    context("DELETE /api/trading/{id}") {
        test("BUYING 상태 사이클 취소 시 202와 LIQUIDATING 반환") {
            every { tradingService.cancel(1L) } returns TradingCancelResult(status = "LIQUIDATING")

            mockMvc.delete("/api/trading/1").andExpect {
                status { isAccepted() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.status") { value(202) }
                jsonPath("$.data.status") { value("LIQUIDATING") }
            }
        }

        test("HOLDING 상태 사이클 취소 시 202와 LIQUIDATING 반환") {
            every { tradingService.cancel(2L) } returns TradingCancelResult(status = "LIQUIDATING")

            mockMvc.delete("/api/trading/2").andExpect {
                status { isAccepted() }
                jsonPath("$.data.status") { value("LIQUIDATING") }
            }
        }

        test("LIQUIDATING 상태 사이클 취소 시 멱등으로 202 반환") {
            every { tradingService.cancel(3L) } returns TradingCancelResult(status = "LIQUIDATING")

            mockMvc.delete("/api/trading/3").andExpect {
                status { isAccepted() }
                jsonPath("$.data.status") { value("LIQUIDATING") }
            }
        }

        test("CLOSED 상태 사이클 취소 시 409 ALREADY_CLOSED") {
            every { tradingService.cancel(4L) } throws AlreadyClosedException(4L)

            mockMvc.delete("/api/trading/4").andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("ALREADY_CLOSED") }
                jsonPath("$.status") { value(409) }
            }
        }

        test("존재하지 않는 사이클 취소 시 404 NOT_FOUND") {
            every { tradingService.cancel(99L) } throws EntityNotFoundException("not found")

            mockMvc.delete("/api/trading/99").andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("NOT_FOUND") }
            }
        }
    }

    context("GET /api/trading/{id}") {
        test("상세 조회 시 DTO shape이 spec과 일치한다") {
            val now = LocalDateTime.of(2026, 5, 3, 10, 0)
            every { tradingQueryService.findById(1L) } returns TradingDetailResult(
                cycleId = 1L,
                stockCode = "005930",
                stockName = "삼성전자",
                status = "INITIATED",
                currentPrice = 70_000L,
                averageBuyPrice = 0L,
                profitRate = 0.0,
                profitAmount = 0L,
                holdingQty = 0,
                buyAttempt = TradingSummaryResult.BuyAttemptInfo(completed = 0),
                totalBoughtQty = 0,
                tpStages = TradingDetailResult.TpStagesInfo(fired2pct = false, fired3pct = false, fired5pct = false),
                splitSellProgress = TradingDetailResult.SplitSellProgressInfo(soldPct = 0),
                breakevenArmed = false,
                trendBreakArmed = false,
                closeReason = null,
                createdAt = now,
                closedAt = null,
                perBuyAmount = 100_000L,
                perBuyQty = null,
                buyIntervalMin = 3,
                splitSellRatio = BigDecimal("0.5"),
                midwayProfitPct = BigDecimal("1.5"),
                breakevenThresholdPct = BigDecimal("0.5"),
                stopLossPct = BigDecimal("2.0"),
                activeSell = null,
                orders = emptyList(),
                executions = emptyList(),
            )

            mockMvc.get("/api/trading/1").andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.data.cycleId") { value(1) }
                jsonPath("$.data.stockCode") { value("005930") }
                jsonPath("$.data.stockName") { value("삼성전자") }
                jsonPath("$.data.status") { value("INITIATED") }
                jsonPath("$.data.currentPrice") { value(70000) }
                jsonPath("$.data.buyAttempt.completed") { value(0) }
                jsonPath("$.data.buyAttempt.total") { value(3) }
                jsonPath("$.data.tpStages.fired2pct") { value(false) }
                jsonPath("$.data.tpStages.fired3pct") { value(false) }
                jsonPath("$.data.tpStages.fired5pct") { value(false) }
                jsonPath("$.data.splitSellProgress.soldPct") { value(0) }
                jsonPath("$.data.activeSell") { doesNotExist() }
                jsonPath("$.data.orders") { isArray() }
                jsonPath("$.data.executions") { isArray() }
            }
        }

        test("없는 id 조회 시 404 NOT_FOUND") {
            every { tradingQueryService.findById(99L) } throws EntityNotFoundException("not found")

            mockMvc.get("/api/trading/99").andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("NOT_FOUND") }
            }
        }
    }
})
