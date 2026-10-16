package judozoo.leadingstock.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.application.LeadingStockService.BreakoutRadarStock
import judozoo.leadingstock.application.LeadingStockService.StockEvaluation
import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.FilterResult
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.MinuteCandles.SwingHigh
import judozoo.leadingstock.domain.MinuteCandles.SwingLow
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
import java.time.LocalDateTime

@WebMvcTest(LeadingStockController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class LeadingStockControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var service: LeadingStockService

    private val at = LocalDateTime.of(2026, 9, 11, 10, 0)

    private fun stock(code: String = "005930", name: String = "삼성전자", tradingValue: Long = 1_000_000_000_000) = LeadingStockSnapshot(
        stockCode = code, stockName = name, currentPrice = 70_000, priceChangeRate = 7.5, tradingValueRank = 3, accumulatedTradingValue = tradingValue,
    )

    @Nested
    inner class 후보_목록 {
        /** 목록은 공개다 — 로그인 없이 부른다. */
        @Test
        fun `순위를 붙여 Python과 같은 모양으로 준다`() {
            every { service.findCandidates(7.0) } returns listOf(stock())

            mvc.get("/api/leading-stocks/candidates").andExpect {
                status { isOk() }
                jsonPath("$.data.totalCount") { value(1) }
                jsonPath("$.data.queriedAt") { exists() }
                jsonPath("$.data.stocks[0]") {
                    value(mapOf("rank" to 1, "stockCode" to "005930", "stockName" to "삼성전자", "currentPrice" to 70_000, "priceChangeRate" to 7.5, "accumulatedTradingValue" to 1_000_000_000_000))
                }
            }
        }

        /** 사용자가 -12~7 중에서 고른다 — 밖의 값은 경계로 붙인다. 안 주면 7. */
        @Test
        fun `등락률 임계값을 범위로 자르고 안 주면 기본값을 쓴다`() {
            every { service.findCandidates(any()) } returns emptyList()

            mvc.get("/api/leading-stocks/candidates") { param("minChangeRate", "99") }
            mvc.get("/api/leading-stocks/candidates") { param("minChangeRate", "-99") }
            mvc.get("/api/leading-stocks/candidates")

            verify(exactly = 2) { service.findCandidates(7.0) }
            verify(exactly = 1) { service.findCandidates(-12.0) }
        }
    }

    @Nested
    inner class 첫_화면_주도주 {
        /** 따로 부르면 호출 사이에 후보 풀이 갱신돼 둘이 다른 순간을 말할 수 있다. 칩에는 이름과 코드만 싣는다. */
        @Test
        fun `미로그인도 주도주와 상한가를 한 응답으로 받는다`() {
            every { service.findLeaders(5) } returns listOf(stock("005930", "삼성전자"), stock("000660", "SK하이닉스"))
            every { service.findLimitUps() } returns listOf(stock("900001", "대성하이텍"))

            mvc.get("/api/leading-stocks/leaders").andExpect {
                status { isOk() }
                content {
                    json(
                        """{"data":{"leaders":[{"rank":1,"stockName":"삼성전자"},{"rank":2,"stockName":"SK하이닉스"}],
                        "limitUps":[{"stockCode":"900001","stockName":"대성하이텍"}]}}""",
                    )
                }
                jsonPath("$.data.limitUps[0].length()") { value(2) }
            }
        }

        /** 첫 화면은 목록 화면에 걸어둔 기준과 무관하게 같은 답을 줘야 한다. */
        @Test
        fun `등락률 파라미터를 받지 않는다`() {
            every { service.findLeaders(any()) } returns emptyList()
            every { service.findLimitUps() } returns emptyList()

            mvc.get("/api/leading-stocks/leaders") { param("minChangeRate", "-12") }.andExpect { jsonPath("$.data.limitUps") { isEmpty() } }

            verify { service.findLeaders(5) }
        }
    }

    @Nested
    inner class 종목_상세 {
        @Test
        fun `종목 상세는 로그인 뒤다`() {
            listOf("/api/leading-stocks/candidates/005930", "/api/leading-stocks/candidates/005930/minute-candles", "/api/leading-stocks/candidates/005930/daily-candles")
                .forEach { mvc.get(it).andExpect { status { isUnauthorized() } } }
        }

        /** 상세 머리가 전일 대비와 시고저를 그린다 — 등락률에서 역산하면 큰 가격대에서 원 단위가 어긋난다. */
        @Test
        fun `필터 평가와 상대거래량, 시세를 Python과 같은 모양으로 준다`() {
            val quote = LeadingStockSnapshot(
                "005930", "삼성전자", 286_500, 3.62, 1, 9_100_600_000_000,
                openingPrice = 283_000, previousClose = 276_500, highPrice = 289_500, lowPrice = 281_500,
            )
            every { service.evaluate("005930") } returns StockEvaluation(
                quote, listOf(FilterResult("거래대금순위", "상위 35위 이내", "1위", true, 1.0, 35.0), FilterResult("시가총액", "3,000억원 이상", "5,000억원", true)),
                2.5, Market.KOSPI,
            )

            mvc.get("/api/leading-stocks/candidates/005930") { cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":{"stockCode":"005930","stockName":"삼성전자","currentPrice":286500,
                        "priceChangeRate":3.62,"relativeVolume":2.5,"market":"KOSPI","filterResults":[
                        {"filterName":"거래대금순위","criteriaDescription":"상위 35위 이내","actualValue":"1위","passed":true,"value":1.0,"threshold":35.0},
                        {"filterName":"시가총액","criteriaDescription":"3,000억원 이상","actualValue":"5,000억원","passed":true,"value":null,"threshold":null}],
                        "openingPrice":283000,"highPrice":289500,"lowPrice":281500,"previousClose":276500,"tradingValue":9100600000000}}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        /** 순위 밖 종목은 누적 거래대금을 받아오지 않는다 — 0원으로 그리면 거래가 없던 것처럼 읽힌다. */
        @Test
        fun `거래대금 순위 밖이면 거래대금과 상대거래량과 시장은 null`() {
            every { service.evaluate("005930") } returns StockEvaluation(stock(tradingValue = 0), emptyList(), null, null)

            mvc.get("/api/leading-stocks/candidates/005930") { cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data.tradingValue") { value(null) }
                jsonPath("$.data.relativeVolume") { value(null) }
                jsonPath("$.data.market") { value(null) }
            }
        }

        @Test
        fun `분봉과 일봉은 기준일을 받아 Python과 같은 모양으로 준다`() {
            every { service.minuteCandles("005930", LocalDate.of(2026, 9, 11)) } returns listOf(MinuteCandle(at, 1, 3, 0, 2, 10, 20))
            every { service.dailyCandles("005930", LocalDate.of(2026, 9, 11)) } returns listOf(DailyCandle(LocalDate.of(2026, 9, 10), 1, 3, 0, 2, 10, 1.5))

            mvc.get("/api/leading-stocks/candidates/005930/minute-candles") { param("date", "2026-09-11"); cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":[{"time":"2026-09-11T10:00:00","open":1,"high":3,"low":0,"close":2,"volume":10,"tradingValue":20}]}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
            mvc.get("/api/leading-stocks/candidates/005930/daily-candles") { param("date", "2026-09-11"); cookie(LoggedIn.cookie) }.andExpect {
                content { json("""{"data":[{"date":"2026-09-10","open":1,"high":3,"low":0,"close":2,"volume":10}]}""", JsonCompareMode.LENIENT) }
            }
        }
    }

    @Nested
    inner class 눌림_돌파 {
        private fun radar(code: String, gap: Double, supportGap: Double? = null) = BreakoutRadarStock(
            stock(code, "종목$code"), SwingHigh(1100, at, gap), supportGap?.let { SwingLow(900, at, it) },
        )

        /** 화면에서 자르면 나머지가 브라우저까지 내려가 읽힌다 — 서버가 자른다. 전체 수는 그대로 알려준다. */
        @Test
        fun `미로그인은 상위 세 개만 받는다`() {
            every { service.breakoutRadar() } returns (0 until 8).map { radar("$it", 0.1 * it) }

            mvc.get("/api/leading-stocks/breakout-radar").andExpect {
                jsonPath("$.data.stocks.length()") { value(3) }
                jsonPath("$.data.totalCount") { value(8) }
                jsonPath("$.data.stocks[0].stockName") { value("종목0") }
            }
        }

        @Test
        fun `로그인하면 전부 받는다`() {
            every { service.breakoutRadar() } returns (0 until 8).map { radar("$it", 0.1 * it) }

            mvc.get("/api/leading-stocks/breakout-radar") { cookie(LoggedIn.cookie) }.andExpect { jsonPath("$.data.stocks.length()") { value(8) } }
        }

        /** 전체 수도 거른 뒤 기준이라 "N개 중 M개"가 같은 모수를 센다. */
        @Test
        fun `선에서 먼 종목은 목록에 없다`() {
            every { service.breakoutRadar() } returns listOf(radar("가까움", 0.5), radar("경계", 3.0), radar("멂", 3.1), radar("아주멂", 12.0))

            mvc.get("/api/leading-stocks/breakout-radar").andExpect {
                content { json("""{"data":{"stocks":[{"stockCode":"가까움"},{"stockCode":"경계"}]}}""") }
                jsonPath("$.data.stocks.length()") { value(2) }
                jsonPath("$.data.totalCount") { value(2) }
            }
        }

        /** 돌파 모드는 돌파선, 눌림 모드는 눌림선 — 각자 3% 안에 든 것만 가까운 순으로 본다. 눌림선을 못 구한 종목은 눌림 모드에서 빠진다. */
        @Test
        fun `모드마다 자기 선으로 거르고 세운다`() {
            every { service.breakoutRadar() } returns listOf(
                radar("A", 1.0, 9.0), radar("B", 2.0, 2.5), radar("C", 3.0, null), radar("D", 4.0, 6.0), radar("F", 6.0, 0.5),
            )

            mvc.get("/api/leading-stocks/breakout-radar") { cookie(LoggedIn.cookie) }.andExpect {
                content { json("""{"data":{"stocks":[{"stockCode":"A"},{"stockCode":"B"},{"stockCode":"C"}]}}""") }
                jsonPath("$.data.stocks.length()") { value(3) }
            }
            mvc.get("/api/leading-stocks/breakout-radar") { param("mode", "support"); cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data.stocks.length()") { value(2) }
                jsonPath("$.data.stocks[1].stockCode") { value("B") }
                jsonPath("$.data.stocks[0]") {
                    value(
                        mapOf(
                            "stockCode" to "F", "stockName" to "종목F", "currentPrice" to 70_000, "priceChangeRate" to 7.5, "peakPrice" to 1100,
                            "peakAt" to "2026-09-11T10:00:00", "gapRate" to 6.0, "troughPrice" to 900, "troughAt" to "2026-09-11T10:00:00",
                            "supportGapRate" to 0.5, "tradingValue" to 1_000_000_000_000,
                        ),
                    )
                }
            }
        }
    }
}
