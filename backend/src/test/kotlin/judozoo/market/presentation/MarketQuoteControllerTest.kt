package judozoo.market.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import judozoo.LoggedIn
import judozoo.market.application.MarketCalendar
import judozoo.market.application.MarketCandles
import judozoo.market.application.MarketQuotes
import judozoo.market.domain.Region
import judozoo.platform.toss.TossMarketIndicator.TossCandle
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

/** 시황 라우트 — 리터럴 경로가 `{market}`으로 새지 않는지, 응답 모양이 Python과 같은지. */
@WebMvcTest(MarketQuoteController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class MarketQuoteControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var quotes: MarketQuotes

    @MockkBean
    private lateinit var candles: MarketCandles

    @MockkBean
    private lateinit var calendar: MarketCalendar

    private fun get(path: String, vararg params: Pair<String, String>) = mvc.get(path) {
        params.forEach { (k, v) -> param(k, v) }
        cookie(LoggedIn.cookie)
    }

    @Nested
    inner class 리터럴_경로_우선 {
        @Test
        fun `나스닥 캔들과 시세는 시장 경로로 새지 않는다`() {
            every { candles.nasdaq("1d") } returns emptyList()
            every { quotes.nasdaqQuote() } returns null

            get("/api/market/nasdaq/candles", "interval" to "1d").andExpect { status { isOk() }; jsonPath("$.data") { isEmpty() } }
            get("/api/market/nasdaq/quote").andExpect { status { isOk() } }
        }

        @Test
        fun `야간선물과 나스닥 선물은 선물 시장 경로로 새지 않는다`() {
            every { quotes.nightFuturesQuote() } returns null
            every { candles.nightMinute() } returns emptyList()
            every { quotes.nasdaqFuturesQuote() } returns null
            every { candles.nasdaqFutures("1m") } returns emptyList()

            get("/api/market/futures/night/quote").andExpect { status { isOk() }; jsonPath("$.data") { value(null as Any?) } }
            get("/api/market/futures/night/candles", "interval" to "1m").andExpect { status { isOk() } }
            get("/api/market/futures/nasdaq/quote").andExpect { status { isOk() } }
            get("/api/market/futures/nasdaq/candles", "interval" to "1m").andExpect { status { isOk() } }
        }

        @Test
        fun `매크로 캔들은 시장 캔들로 새지 않는다`() {
            every { candles.macro("WTI", "1d") } returns emptyList()

            get("/api/market/macro/candles", "target" to "WTI", "interval" to "1d").andExpect { status { isOk() } }
        }

        @Test
        fun `휴장 상태는 시장 경로로 새지 않는다`() {
            every { calendar.isHoliday(Region.KR) } returns false
            every { calendar.previousOpenDay(any()) } returns null

            get("/api/market/calendar/status", "region" to "KR").andExpect {
                status { isOk() }
                content { json("""{"data":{"isHoliday":false,"previousOpenDay":null}}""") }
            }
        }
    }

    @Nested
    inner class 시장_경로 {
        /** 토스 캔들 시각은 초까지 적는다 — Python `time.isoformat()`과 같다. */
        @Test
        fun `코스피 캔들은 날짜와 시각을 나눠 준다`() {
            every { candles.indexDaily(Market.KOSPI, 90) } returns listOf(TossCandle(LocalDateTime.of(2026, 9, 28, 9, 0), 1.0, 2.0, 0.5, 1.5, 100.0))

            get("/api/market/KOSPI/candles", "interval" to "1d").andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":[{"date":"2026-09-28","time":"09:00:00","open":1.0,"high":2.0,"low":0.5,"close":1.5,"volume":100.0}]}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `정의에 없는 시장은 400`() {
            get("/api/market/NIKKEI/candles", "interval" to "1d").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_PARAMETER") }
            }
        }

        @Test
        fun `알 수 없는 지역도 400`() {
            get("/api/market/calendar/status", "region" to "JP").andExpect { status { isBadRequest() } }
        }

        @Test
        fun `선물 시세에는 투자자 수급을 담지 않는다`() {
            every { quotes.futuresQuote(Market.KOSPI) } returns MarketQuotes.FuturesQuote(300.0, 1.2, 299.0, 1.0, 0.1, 1000, 20, 10, "2026-10-08")

            get("/api/market/futures/KOSPI/quote").andExpect {
                content {
                    json(
                        """{"data":{"futuresPrice":300.0,"changeRate":1.2,"spot":299.0,"basis":1.0,"dprt":0.1,"openInterest":1000,
                        "openInterestChange":20,"rmnnDays":10,"expiryDate":"2026-10-08"}}""",
                        JsonCompareMode.LENIENT,
                    )
                }
                jsonPath("$.data.investors") { doesNotExist() }
            }
        }
    }

    @Nested
    inner class 직전_개장일 {
        /** 로그인 없이 연다 — 휴장 배너는 공개다. */
        @Test
        fun `국내는 직전 개장일을 싣는다`() {
            every { calendar.isHoliday(Region.KR) } returns true
            every { calendar.previousOpenDay(any()) } returns LocalDate.of(2026, 9, 23)

            mvc.get("/api/market/calendar/status") { param("region", "KR") }.andExpect { jsonPath("$.data.previousOpenDay") { value("2026-09-23") } }
        }

        /** 개장일 목록(KIS)이 국내 것뿐이다 — 미국 날짜를 국내 달력으로 짚으면 틀린다. */
        @Test
        fun `해외는 싣지 않는다`() {
            every { calendar.isHoliday(Region.US) } returns false

            mvc.get("/api/market/calendar/status") { param("region", "US") }.andExpect { jsonPath("$.data.previousOpenDay") { value(null as Any?) } }
        }
    }

    @Nested
    inner class 매크로_시세 {
        @Test
        fun `미국채 10년 금리가 다른 지표와 함께 실린다`() {
            val quote = MarketQuotes.QuoteResult(4.2, 4.1, 0.1, 2.4)
            every { quotes.macroQuotes() } returns MarketQuotes.MacroQuotes(quote, null, quote, quote)

            get("/api/market/macro/quotes").andExpect {
                jsonPath("$.data.us10y.price") { value(4.2) }
                jsonPath("$.data.usdKrw.prevClose") { value(4.1) }
                jsonPath("$.data.wti") { value(null as Any?) }
            }
        }
    }
}
