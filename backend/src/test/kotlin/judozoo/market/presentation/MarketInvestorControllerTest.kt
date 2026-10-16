package judozoo.market.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import judozoo.LoggedIn
import judozoo.market.application.MarketInvestors
import judozoo.market.domain.InvestorSession
import judozoo.market.domain.InvestorSessions
import judozoo.market.domain.Nets
import judozoo.market.domain.SessionOrg
import judozoo.stock.domain.Market
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate

@WebMvcTest(MarketInvestorController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class MarketInvestorControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var investors: MarketInvestors

    @Nested
    inner class 경로 {
        @Test
        fun `오늘의 수급은 시장 수급 경로로 새지 않는다`() {
            every { investors.todayNets() } returns emptyList()

            mvc.get("/api/market/investor/today") { cookie(LoggedIn.cookie) }.andExpect { status { isOk() }; jsonPath("$.data") { isEmpty() } }
        }

        @Test
        fun `수급은 로그인 뒤다`() {
            listOf("/api/market/investor/today", "/api/market/KOSPI/investor/sessions", "/api/market/futures/KOSDAQ/investor/daily").forEach {
                mvc.get(it).andExpect { status { isUnauthorized() } }
            }
        }
    }

    @Nested
    inner class 응답_모양 {
        @Test
        fun `세션 수급은 실제로 쓴 날짜와 함께 Python과 같은 모양으로 준다`() {
            val nets = Nets(100, -50, -50, 0, SessionOrg(1, 2, 3, 4, 5, 6, 7))
            every { investors.investorSessions(Market.KOSPI, LocalDate.of(2026, 9, 14)) } returns
                InvestorSessions(LocalDate.of(2026, 9, 11), listOf(InvestorSessions.Net(InvestorSession.PRE_MARKET, nets)))

            mvc.get("/api/market/KOSPI/investor/sessions") {
                param("date", "2026-09-14")
                cookie(LoggedIn.cookie)
            }.andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":{"date":"2026-09-11","sessions":[{"name":"프리마켓","time":"08:00~09:00",
                        "nets":{"individual":100,"foreign":-50,"institution":-50,"otherCorp":0,"breakdown":{"financialInvestmentEok":1,
                        "insuranceEok":2,"otherFinanceEok":3,"trustEok":4,"privateEquityEok":5,"pensionFundEok":6,"bankEok":7}},"delta":null}]}}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `오늘의 수급은 시장 이름과 기준일을 싣는다`() {
            every { investors.todayNets() } returns listOf(
                MarketInvestors.TodayNet(Market.KOSPI, false, 2653.81, 1.24, MarketInvestors.TodayNetInvestors(200, 20, -10, 5), LocalDate.of(2026, 9, 30)),
            )

            mvc.get("/api/market/investor/today") { cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":[{"market":"KOSPI","futures":false,"indexValue":2653.81,"changeRate":1.24,
                        "nets":{"individual":200,"foreign":20,"institution":-10,"otherCorp":5},"tradeDate":"2026-09-30"}]}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
        }
    }
}
