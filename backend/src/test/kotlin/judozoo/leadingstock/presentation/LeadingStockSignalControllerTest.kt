package judozoo.leadingstock.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.leadingstock.application.LeadingStockSignals
import judozoo.leadingstock.domain.IndexMinuteCandle
import judozoo.leadingstock.domain.InvestorType
import judozoo.leadingstock.domain.MarketSignalEvent
import judozoo.leadingstock.domain.NetTradeSide
import judozoo.leadingstock.domain.SignalEvent
import judozoo.leadingstock.domain.SpikeDirection
import judozoo.market.domain.MarketInvestorSnapshot
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

@WebMvcTest(LeadingStockSignalController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class LeadingStockSignalControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var signals: LeadingStockSignals

    private val day = LocalDate.of(2026, 9, 11)
    private val at = day.atTime(10, 0)

    private fun event(i: Int, rate: Double = 5.0) = SignalEvent(
        at.plusMinutes(i.toLong()), day, "%06d".format(i), "종목%02d".format(i), "VOLUME_SPIKE", 1000, rate.toFloat(), 100,
        3.5f, 2_000_000_000, SpikeDirection.BUY, at, at,
    )

    private fun events(vararg rates: Double) = rates.mapIndexed { i, r -> event(i, r) }.reversed()

    @Nested
    inner class 시그널_로그 {
        @Test
        fun `Python과 같은 모양으로 준다`() {
            every { signals.signalEventsOn(day) } returns listOf(event(0))

            mvc.get("/api/leading-stocks/signal-events") { param("date", "2026-09-11"); cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":{"date":"2026-09-11","totalCount":1,"events":[{"occurredAt":"2026-09-11T10:00:00",
                        "stockCode":"000000","stockName":"종목00","eventType":"VOLUME_SPIKE","currentPrice":1000,"priceChangeRate":5.0,"tradingValue":100,
                        "gapRate":null,"spikeRatio":3.5,"minuteTradingValue":2000000000,"spikeDirection":"BUY","ma":null}]}}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `미로그인은 최신 세 건만 받는다`() {
            every { signals.signalEventsOn(any()) } returns events(*DoubleArray(12) { 5.0 })

            mvc.get("/api/leading-stocks/signal-events") { param("date", "2026-09-11") }.andExpect {
                status { isOk() }
                jsonPath("$.data.events.length()") { value(3) }
                jsonPath("$.data.totalCount") { value(12) }
                jsonPath("$.data.events[0].stockName") { value("종목11") }
            }
        }

        @Test
        fun `미로그인은 내린 종목의 전이를 못 본다`() {
            every { signals.signalEventsOn(any()) } returns events(-8.0, -0.1, 0.0, 9.0)

            mvc.get("/api/leading-stocks/signal-events") { param("date", "2026-09-11") }.andExpect {
                jsonPath("$.data.events[0].stockName") { value("종목03") }
                jsonPath("$.data.events[1].stockName") { value("종목02") }
                jsonPath("$.data.events.length()") { value(2) }
                jsonPath("$.data.totalCount") { value(2) }
            }
        }

        @Test
        fun `로그인하면 등락률과 건수에 상관없이 전부 받는다`() {
            every { signals.signalEventsOn(any()) } returns events(*DoubleArray(12) { if (it == 0) -8.0 else 5.0 })

            mvc.get("/api/leading-stocks/signal-events") { param("date", "2026-09-11"); cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data.events.length()") { value(12) }
                jsonPath("$.data.totalCount") { value(12) }
            }
        }
    }

    @Nested
    inner class 시장_시그널_로그 {
        @Test
        fun `시장 시그널은 로그인 뒤다`() {
            listOf("/api/leading-stocks/market-signal-events", "/api/leading-stocks/market/investor-net-buy", "/api/leading-stocks/index/KOSPI/minute-candles")
                .forEach { mvc.get(it).andExpect { status { isUnauthorized() } } }
        }

        @Test
        fun `단계에 해당하는 임계액을 함께 Python과 같은 모양으로 준다`() {
            every { signals.marketEventsOn(day) } returns listOf(
                MarketSignalEvent(at, day, "NET_BUY_LEVEL", Market.KOSPI, NetTradeSide.BUY, InvestorType.FOREIGN, 3, 32_000, indexValueFloat = 2653.81f, changeRateFloat = 1.2f),
                MarketSignalEvent(at, day, "MA_REBOUND", Market.KOSDAQ, NetTradeSide.BUY),
            )

            mvc.get("/api/leading-stocks/market-signal-events") { param("date", "2026-09-11"); cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":{"date":"2026-09-11","totalCount":2,"events":[
                        {"occurredAt":"2026-09-11T10:00:00","kind":"NET_BUY_LEVEL","market":"KOSPI","side":"BUY","investor":"FOREIGN","level":3,
                         "thresholdEok":30000,"netAmountEok":32000,"extremeAmountEok":null,"indexValue":2653.81,"changeRate":1.2},
                        {"occurredAt":"2026-09-11T10:00:00","kind":"MA_REBOUND","market":"KOSDAQ","side":"BUY","investor":null,"level":null,
                         "thresholdEok":null,"netAmountEok":null,"extremeAmountEok":null,"indexValue":null,"changeRate":null}]}}""",
                        JsonCompareMode.LENIENT,
                    )
                }
                jsonPath("$.data.events[1].thresholdEok") { value(null) }
            }
        }
    }

    @Nested
    inner class 시장_수급 {
        private val snapshot = mockk<MarketInvestorSnapshot> {
            every { foreignEok } returns 200
            every { institutionEok } returns -10
            every { individualEok } returns -190
            every { otherCorpEok } returns 0
            every { financialInvestmentEok } returns 1
            every { trustEok } returns 2
            every { pensionFundEok } returns 3
            every { privateEquityEok } returns 4
            every { insuranceEok } returns 5
            every { bankEok } returns 6
            every { indexValue } returns 2600.5
            every { changeRate } returns 1.2
        }

        @Test
        fun `라이브는 가장 최근 스냅샷을 지수값과 함께 준다`() {
            every { signals.investorNetBuy(null, any()) } returns mapOf(Market.KOSPI to snapshot)

            mvc.get("/api/leading-stocks/market/investor-net-buy") { cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":[{"market":"KOSPI","foreignEok":200,"institutionEok":-10,"individualEok":-190,"otherCorpEok":0,
                        "financialInvestmentEok":1,"trustEok":2,"pensionFundEok":3,"privateEquityEok":4,"insuranceEok":5,"bankEok":6,
                        "indexValue":2600.5,"changeRate":1.2}]}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
        }

        @Test
        fun `시그널 시각을 주면 그 시점 값이고 지수값은 비운다`() {
            every { signals.investorNetBuy(at, any()) } returns mapOf(Market.KOSPI to snapshot)

            mvc.get("/api/leading-stocks/market/investor-net-buy") { param("at", "2026-09-11T10:00:00"); cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data[0].foreignEok") { value(200) }
                jsonPath("$.data[0].indexValue") { value(null) }
                jsonPath("$.data[0].changeRate") { value(null) }
            }
            verify { signals.investorNetBuy(at, any()) }
        }
    }

    @Nested
    inner class 지수_분봉 {
        @Test
        fun `시장 이름은 대소문자를 가리지 않는다`() {
            every { signals.indexMinuteCandles(Market.KOSPI, day) } returns listOf(IndexMinuteCandle(at, 1.5, 2.0, 1.0, 1.75, 10))

            mvc.get("/api/leading-stocks/index/kospi/minute-candles") { param("date", "2026-09-11"); cookie(LoggedIn.cookie) }.andExpect {
                content { json("""{"data":[{"time":"2026-09-11T10:00:00","open":1.5,"high":2.0,"low":1.0,"close":1.75,"volume":10}]}""", JsonCompareMode.LENIENT) }
            }
        }

        @Test
        fun `알 수 없는 시장은 INTERNAL_ERROR 봉투로 떨어진다`() {
            mvc.get("/api/leading-stocks/index/NIKKEI/minute-candles") { cookie(LoggedIn.cookie) }.andExpect {
                status { isInternalServerError() }
                jsonPath("$.code") { value("INTERNAL_ERROR") }
            }
        }
    }
}
