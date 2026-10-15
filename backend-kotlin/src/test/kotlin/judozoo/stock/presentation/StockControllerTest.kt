package judozoo.stock.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import judozoo.LoggedIn
import judozoo.stock.application.StockCatalog
import judozoo.stock.application.StockInvestorService
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate

@WebMvcTest(StockController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class StockControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var catalog: StockCatalog

    @MockkBean
    private lateinit var investors: StockInvestorService

    @Nested
    inner class 종목_검색_API {
        @Test
        fun `검색 결과를 Python과 같은 모양으로 준다`() {
            every { catalog.search("삼성") } returns listOf(
                StockCatalog.SearchResult("207940", "삼성바이오로직스", null),
                StockCatalog.SearchResult("AAPL", "애플", "NAS"),
            )

            mvc.get("/api/stocks/search") {
                param("q", "삼성")
                cookie(LoggedIn.cookie)
            }.andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.data[0].stockCode") { value("207940") }
                jsonPath("$.data[0].stockName") { value("삼성바이오로직스") }
                // 국내 종목은 거래소가 비어 있다 — 필드는 빠지지 않고 null로 온다
                jsonPath("$.data[0].exchange") { value(null as Any?) }
                jsonPath("$.data[1].exchange") { value("NAS") }
            }
        }

        @Test
        fun `결과가 없으면 빈 배열을 준다`() {
            every { catalog.search(any()) } returns emptyList()

            mvc.get("/api/stocks/search") {
                param("q", "없는종목")
                cookie(LoggedIn.cookie)
            }.andExpect { jsonPath("$.data") { isEmpty() } }
        }

        @Test
        fun `질의가 없으면 400을 준다`() {
            mvc.get("/api/stocks/search") { cookie(LoggedIn.cookie) }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_PARAMETER") }
            }
        }

        @Test
        fun `로그인하지 않으면 막힌다`() {
            mvc.get("/api/stocks/search") { param("q", "삼성") }.andExpect { status { isUnauthorized() } }
        }
    }

    @Nested
    inner class 종목_투자자_수급_API {
        @Test
        fun `백만원 단위와 날짜를 Python과 같은 모양으로 준다`() {
            every { investors.investorDailyHistory("005930", 5) } returns listOf(
                StockInvestorService.InvestorDay(
                    LocalDate.of(2026, 9, 11), 1200, -800, -400, 0,
                    StockInvestorService.OrgBreakdown(100, 200, 150, -20, -50, -10, 0),
                ),
            )

            mvc.get("/api/stocks/005930/investor/daily") {
                param("count", "5")
                cookie(LoggedIn.cookie)
            }.andExpect {
                jsonPath("$.data[0].date") { value("2026-09-11") }
                jsonPath("$.data[0].individualMillion") { value(1200) }
                jsonPath("$.data[0].foreignMillion") { value(-800) }
                jsonPath("$.data[0].breakdown.pensionFundMillion") { value(150) }
            }
        }

        @Test
        fun `개수를 안 주면 10거래일이다`() {
            every { investors.investorDailyHistory("005930", 10) } returns emptyList()

            mvc.get("/api/stocks/005930/investor/daily") { cookie(LoggedIn.cookie) }.andExpect { status { isOk() } }
        }
    }
}
