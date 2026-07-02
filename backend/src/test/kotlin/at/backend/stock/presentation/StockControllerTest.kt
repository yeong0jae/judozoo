package at.backend.stock.presentation

import at.backend.library.web.GlobalExceptionHandler
import at.backend.stock.application.StockService
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class StockControllerTest : FunSpec({

    val stockService = mockk<StockService>()
    val mockMvc = MockMvcBuilders
        .standaloneSetup(StockController(stockService))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    context("GET /api/stocks/search") {
        test("검색 결과 목록을 200으로 반환한다") {
            every { stockService.search("삼성") } returns listOf(
                StockService.StockSearchResult(stockCode = "005930", stockName = "삼성전자"),
                StockService.StockSearchResult(stockCode = "207940", stockName = "삼성바이오로직스"),
            )

            mockMvc.get("/api/stocks/search") {
                param("q", "삼성")
            }.andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.data.length()") { value(2) }
                jsonPath("$.data[0].stockCode") { value("005930") }
                jsonPath("$.data[0].stockName") { value("삼성전자") }
                jsonPath("$.data[1].stockCode") { value("207940") }
            }
        }

        test("결과가 없으면 빈 배열을 반환한다") {
            every { stockService.search(any()) } returns emptyList()

            mockMvc.get("/api/stocks/search") {
                param("q", "없는종목")
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.length()") { value(0) }
            }
        }
    }
})
