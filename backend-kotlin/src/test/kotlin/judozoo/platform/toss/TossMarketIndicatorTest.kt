package judozoo.platform.toss

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.library.brokertoken.TokenSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

class TossMarketIndicatorTest {
    private val stub = TossStub()
    private val indicator = TossMarketIndicator(stub.client)

    @AfterEach
    fun 정리() = stub.close()

    private fun amount(buy: String?, sell: String?) =
        JsonMapper.builder().build().readTree("""{"buyAmount":${buy?.let { "\"$it\"" }},"sellAmount":${sell?.let { "\"$it\"" }}}""")

    private val record = """{"date":"2026-09-11","updatedAt":"2026-09-11T18:10:00+09:00",
        "individual":{"buyAmount":"300000000","sellAmount":"100000000"},
        "foreigner":{"buyAmount":"100000000","sellAmount":"400000000"},
        "institution":{"buyAmount":"500000000","sellAmount":"400000000","breakdown":{
          "pensionFund":{"buyAmount":"200000000","sellAmount":"100000000"},
          "trust":{"buyAmount":"0","sellAmount":"100000000"}}},
        "otherCorporation":{"buyAmount":"0","sellAmount":"0"}}"""

    @Nested
    inner class 순매수_환산 {
        /** floor(x + 0.5) — Python 기본 round()의 은행가 반올림과 갈린다. 옛 Kotlin `roundToLong()`과 같은 값이다. */
        @Test
        fun `매수에서 매도를 빼 억원으로 반올림한다`() {
            TossMarketIndicator.netEok(amount("300000000", "100000000")) shouldBe 2 // +2억
            TossMarketIndicator.netEok(amount("100000000", "300000000")) shouldBe -2 // -2억
            TossMarketIndicator.netEok(amount("250000000", "0")) shouldBe 3 // 2.5억 → 올림
            TossMarketIndicator.netEok(amount("0", "250000000")) shouldBe -2 // -2.5억 → +무한대 쪽으로
            TossMarketIndicator.netEok(amount(null, null)) shouldBe 0
        }

        @Test
        fun `없으면 0`() {
            TossMarketIndicator.netEok(null) shouldBe 0
        }
    }

    @Nested
    inner class 투자자_매매대금 {
        @Test
        fun `투자자별 순매수를 억원으로 준다`() {
            stub.respond(INVESTOR, """{"result":{"records":[$record]}}""")

            val r = indicator.fetchInvestorTrading("KOSPI")[0]

            r.date shouldBe LocalDate.of(2026, 9, 11)
            listOf(r.individualNetEok, r.foreignNetEok, r.institutionNetEok) shouldBe listOf(2L, -3L, 1L)
        }

        @Test
        fun `기관 합계는 세부 합이 아니라 자신의 매수매도로 낸다`() {
            stub.respond(INVESTOR, """{"result":{"records":[$record]}}""")

            val r = indicator.fetchInvestorTrading("KOSPI")[0]

            r.institutionNetEok shouldBe 1 // 5억 − 4억
            r.breakdown.pensionFundEok shouldBe 1
            r.breakdown.trustEok shouldBe -1
        }

        @Test
        fun `날짜가 없는 레코드는 버린다`() {
            stub.respond(INVESTOR, """{"result":{"records":[{"date":null},$record]}}""")

            indicator.fetchInvestorTrading("KOSPI") shouldHaveSize 1
        }

        @Test
        fun `기준일을 주면 요청에 싣는다`() {
            stub.server.stubFor(
                get(urlPathEqualTo(INVESTOR)).withQueryParam("until", equalTo("2026-09-11"))
                    .willReturn(okJson("""{"result":{"records":[$record]}}""")),
            )

            indicator.fetchInvestorTrading("KOSPI", until = LocalDate.of(2026, 9, 11)) shouldHaveSize 1
        }

        @Test
        fun `실패하면 빈 목록`() {
            stub.respond(INVESTOR, "{}", status = 500)

            indicator.fetchInvestorTrading("KOSPI").shouldBeEmpty()
        }
    }

    @Nested
    inner class 캔들 {
        @Test
        fun `OHLCV와 다음 커서를 준다`() {
            stub.respond(
                CANDLES,
                """{"result":{"candles":[{"timestamp":"2026-09-11T09:00:00+09:00","openPrice":"2600.0","highPrice":"2650.0",
                "lowPrice":"2590.0","closePrice":"2640.0","volume":"1200"}],"nextBefore":"cursor-1"}}""",
            )

            val page = indicator.fetchCandles("KOSPI", "1d", 10)

            page.nextBefore shouldBe "cursor-1"
            val c = page.candles[0]
            listOf(c.open, c.high, c.low, c.close, c.volume) shouldBe listOf(2600.0, 2650.0, 2590.0, 2640.0, 1200.0)
            c.timestamp.hour shouldBe 9
        }

        @Test
        fun `OHLC가 하나라도 없으면 캔들이 아니다`() {
            stub.respond(
                CANDLES,
                """{"result":{"candles":[{"timestamp":"2026-09-11T09:00:00+09:00","openPrice":"2600.0","highPrice":null,
                "lowPrice":"2590.0","closePrice":"2640.0"}]}}""",
            )

            indicator.fetchCandles("KOSPI", "1d", 10).candles.shouldBeEmpty()
        }

        @Test
        fun `실패하면 빈 페이지`() {
            stub.fail(CANDLES)

            val page = indicator.fetchCandles("KOSPI", "1m", 10)

            page.candles.shouldBeEmpty()
            page.nextBefore.shouldBeNull()
        }
    }

    @Nested
    inner class 지수_캔들_리미터 {
        /** 1분봉 차트 하나가 페이지를 연달아 받는다 — 리미터 없이는 요청 하나가 한도를 넘는다. */
        @Test
        fun `페이지마다 지수 캔들 한도를 얻는다`() {
            val registry = SimpleMeterRegistry()
            val client = TossClient(
                TossProperties(baseUrl = stub.server.baseUrl(), indicatorChartPermitsPerSecond = 100),
                object : TokenSource {
                    override fun get() = "tok"
                    override fun invalidate() {}
                },
                registry,
            )
            stub.respond(CANDLES, """{"result":{"candles":[],"nextBefore":null}}""")

            TossMarketIndicator(client).fetchCandles("KOSPI", "1m", 200)
            TossMarketIndicator(client).fetchCandles("KOSPI", "1m", 200, before = "2026-09-28T09:00:00+09:00")

            registry.timer("rate_limiter_wait", "limiter", "toss-indicator-chart").count() shouldBe 2
        }
    }

    companion object {
        private const val INVESTOR = "/api/v1/market-indicators/KOSPI/investor-trading"
        private const val CANDLES = "/api/v1/market-indicators/KOSPI/candles"
    }
}
