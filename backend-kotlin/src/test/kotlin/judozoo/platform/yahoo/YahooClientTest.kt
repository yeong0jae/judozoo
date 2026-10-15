package judozoo.platform.yahoo

import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class YahooClientTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val yahoo = YahooClient(YahooProperties(baseUrl = server.baseUrl()), SimpleMeterRegistry())

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    private fun chart(meta: String? = null, timestamps: String? = null, quote: String? = null): String {
        val parts = listOfNotNull(
            meta?.let { "\"meta\":$it" },
            timestamps?.let { "\"timestamp\":$it" },
            quote?.let { "\"indicators\":{\"quote\":[$it]}" },
        )
        return """{"chart":{"result":[{${parts.joinToString(",")}}],"error":null}}"""
    }

    private fun respond(symbolPath: String, json: String) {
        server.stubFor(get(urlPathEqualTo("/v8/finance/chart/$symbolPath")).willReturn(okJson(json)))
    }

    @Nested
    inner class 시세_요약 {
        @Test
        fun `현재가와 직전 세션 종가를 읽는다`() {
            server.stubFor(
                get(urlPathMatching("/v8/finance/chart/NQ(=|%3D)F"))
                    .willReturn(okJson(chart(meta = """{"shortName":"Nasdaq 100","regularMarketPrice":20100.5,"previousClose":20000.0}"""))),
            )

            val quote = yahoo.fetchQuote("NQ=F")!!

            quote.name shouldBe "Nasdaq 100"
            quote.price shouldBe 20100.5
            quote.prevClose shouldBe 20000.0
        }

        /** chartPreviousClose는 range에 따라 값이 달라져 기준가로 쓰기 부적절하다. */
        @Test
        fun `조회창 직전 종가보다 전일종가를 우선한다`() {
            respond("AAPL", chart(meta = """{"regularMarketPrice":100.0,"previousClose":90.0,"chartPreviousClose":80.0}"""))

            yahoo.fetchQuote("AAPL")!!.prevClose shouldBe 90.0
        }

        @Test
        fun `전일종가가 없으면 조회창 직전 종가로 대체한다`() {
            respond("AAPL", chart(meta = """{"regularMarketPrice":100.0,"chartPreviousClose":80.0}"""))

            yahoo.fetchQuote("AAPL")!!.prevClose shouldBe 80.0
        }

        @Test
        fun `현재가가 없으면 시세를 주지 않는다`() {
            respond("AAPL", chart(meta = """{"previousClose":90.0}"""))

            yahoo.fetchQuote("AAPL").shouldBeNull()
        }
    }

    /** meta는 정규장 값만 담는다. 프리·애프터마켓 중엔 마지막 캔들을 현재가로 써야 한다. */
    @Nested
    inner class 확장시간_시세 {
        @Test
        fun `정규장 이후 체결이 있으면 마지막 캔들을 현재가로 쓴다`() {
            respond(
                "AAPL",
                chart(
                    meta = """{"regularMarketPrice":200.0,"regularMarketTime":1000,"previousClose":190.0}""",
                    timestamps = "[900,1000,1100]", // 1100은 정규장 마감 이후
                    quote = """{"close":[198.0,200.0,205.0]}""",
                ),
            )

            val quote = yahoo.fetchExtendedQuote("AAPL")!!

            quote.price shouldBe 205.0 // 애프터마켓 체결이 현재가
            quote.prevClose shouldBe 200.0 // 직전 정규장 종가가 기준가
        }

        @Test
        fun `정규장 중에는 meta를 그대로 쓴다`() {
            respond(
                "AAPL",
                chart(
                    meta = """{"regularMarketPrice":200.0,"regularMarketTime":1100,"previousClose":190.0}""",
                    timestamps = "[900,1000,1100]",
                    quote = """{"close":[198.0,199.0,200.0]}""",
                ),
            )

            val quote = yahoo.fetchExtendedQuote("AAPL")!!

            quote.price shouldBe 200.0
            quote.prevClose shouldBe 190.0
        }

        @Test
        fun `거래가 없던 분은 건너뛰고 마지막 체결을 찾는다`() {
            respond(
                "AAPL",
                chart(
                    meta = """{"regularMarketPrice":200.0,"regularMarketTime":1000,"previousClose":190.0}""",
                    timestamps = "[1100,1200,1300]",
                    quote = """{"close":[205.0,null,null]}""",
                ),
            )

            yahoo.fetchExtendedQuote("AAPL")!!.price shouldBe 205.0
        }
    }

    @Nested
    inner class 캔들 {
        @Test
        fun `시각을 KST로 바꿔 날짜와 시간을 나눈다`() {
            // 1735689600 = 2025-01-01 00:00:00 UTC = 2025-01-01 09:00:00 KST
            respond(
                "AAPL",
                chart(timestamps = "[1735689600]", quote = """{"close":[100.0],"open":[99.0],"high":[101.0],"low":[98.0],"volume":[5]}"""),
            )

            val bar = yahoo.fetchCandles("AAPL", "1m", "1d")[0]

            bar.date shouldBe "2025-01-01"
            bar.time shouldBe "09:00:00"
            listOf(bar.open, bar.high, bar.low, bar.close, bar.volume) shouldBe listOf(99.0, 101.0, 98.0, 100.0, 5.0)
        }

        @Test
        fun `거래가 없던 분은 버린다`() {
            respond("AAPL", chart(timestamps = "[1735689600,1735689660]", quote = """{"close":[100.0,null]}"""))

            yahoo.fetchCandles("AAPL", "1m", "1d") shouldHaveSize 1
        }

        @Test
        fun `시가고가저가가 없으면 종가로 채운다`() {
            respond("AAPL", chart(timestamps = "[1735689600]", quote = """{"close":[100.0]}"""))

            val bar = yahoo.fetchCandles("AAPL", "1m", "1d")[0]

            listOf(bar.open, bar.high, bar.low, bar.volume) shouldBe listOf(100.0, 100.0, 100.0, 0.0)
        }

        /** 세션 마감 봉이 거래량 0짜리 중복으로 한 번 더 온다. 차트는 시각이 유일해야 한다. */
        @Test
        fun `같은 시각이 두 번 오면 뒤엣것으로 덮는다`() {
            respond("AAPL", chart(timestamps = "[1735689600,1735689600]", quote = """{"close":[100.0,102.0],"volume":[5,0]}"""))

            val bars = yahoo.fetchCandles("AAPL", "1m", "1d")

            bars shouldHaveSize 1
            bars[0].close shouldBe 102.0
        }

        @Test
        fun `시각 오름차순으로 준다`() {
            respond("AAPL", chart(timestamps = "[1735689720,1735689600,1735689660]", quote = """{"close":[102.0,100.0,101.0]}"""))

            yahoo.fetchCandles("AAPL", "1m", "1d").map { it.close } shouldBe listOf(100.0, 101.0, 102.0)
        }
    }

    @Nested
    inner class 요청_형식 {
        @Test
        fun `차단을 피하려 User-Agent를 붙인다`() {
            respond("AAPL", chart(meta = "{}"))

            yahoo.fetchQuote("AAPL")

            server.verify(getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL")).withHeader("User-Agent", containing("Mozilla")))
        }

        @Test
        fun `확장시간 조회에만 includePrePost를 붙인다`() {
            respond("AAPL", chart(meta = """{"regularMarketPrice":1.0}"""))

            yahoo.fetchQuote("AAPL")
            server.verify(1, getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL")).withQueryParam("includePrePost", absent()))

            yahoo.fetchExtendedQuote("AAPL")
            server.verify(1, getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL")).withQueryParam("includePrePost", equalTo("true")))
        }
    }

    @Nested
    inner class 응답이_정상이_아니면 {
        @Test
        fun `야후가 오류를 주면 빈 결과로 처리한다`() {
            respond("AAPL", """{"chart":{"result":null,"error":{"code":"Not Found"}}}""")

            yahoo.fetchQuote("AAPL").shouldBeNull()
            yahoo.fetchCandles("AAPL", "1m", "1d").shouldBeEmpty()
        }

        @Test
        fun `HTTP 오류에도 예외를 밖으로 던지지 않는다`() {
            server.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL")).willReturn(aResponse().withStatus(500)))

            yahoo.fetchQuote("AAPL").shouldBeNull()
            yahoo.fetchCandles("AAPL", "1m", "1d").shouldBeEmpty()
        }

        @Test
        fun `연결이 끊겨도 빈 결과를 준다`() {
            server.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

            yahoo.fetchCandles("AAPL", "1m", "1d").shouldBeEmpty()
        }
    }
}
