package judozoo.platform.kiwoom

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KiwoomMarketTest {
    private val stub = KiwoomStub()
    private val market = KiwoomMarket(stub.client, stub.clock)

    @AfterEach
    fun 정리() = stub.close()

    private fun rankRow(
        code: String, name: String, curPrc: String = "+71500", rank: String = "1", amount: String = "1000",
        rate: String = "+3.5", sig: String = "2", volume: String = "123456",
    ) = """{"stk_cd":"$code","stk_nm":"$name","cur_prc":"$curPrc","now_rank":"$rank","flu_rt":"$rate",
        "trde_prica":"$amount","pred_pre_sig":"$sig","now_trde_qty":"$volume"}"""

    private fun respondFor(path: String, stockCode: String, json: String) {
        stub.server.stubFor(
            post(urlEqualTo(path)).withRequestBody(matchingJsonPath("$.stk_cd", equalTo(stockCode))).willReturn(okJson(json)),
        )
    }

    @Nested
    inner class 거래대금_상위 {
        @Test
        fun `순위와 거래대금을 읽는다`() {
            stub.respond(RANK, """{"return_code":0,"trde_prica_upper":[${rankRow("005930", "삼성전자", amount = "5000")}]}""")

            val result = market.fetchTopTradingValueStocks()

            result shouldHaveSize 1
            val s = result[0]
            (s.stockCode to s.stockName) shouldBe ("005930" to "삼성전자")
            s.currentPrice shouldBe 71500
            s.accumulatedTradingValue shouldBe 5_000_000_000
            s.accumulatedVolume shouldBe 123456
        }

        @Test
        fun `등락부호로 상한가를 읽는다`() {
            stub.respond(
                RANK,
                """{"return_code":0,"trde_prica_upper":[${rankRow("900001", "잠긴잡주", rate = "+29.94", sig = "1")},
                ${rankRow("005930", "삼성전자", rank = "2")}]}""",
            )

            market.fetchTopTradingValueStocks().map { it.limitUp } shouldBe listOf(true, false)
        }

        @Test
        fun `많이 올랐어도 부호가 상한이 아니면 상한가가 아니다`() {
            stub.respond(RANK, """{"return_code":0,"trde_prica_upper":[${rankRow("0200G0", "한국제17호스팩", rate = "+153.00", sig = "2")}]}""")

            val result = market.fetchTopTradingValueStocks()

            result[0].priceChangeRate shouldBe 153.0
            result[0].limitUp shouldBe false
        }

        @Test
        fun `목록이 없으면 예외로 올린다`() {
            stub.respond(RANK, """{"return_code":0,"return_msg":"no data"}""")

            shouldThrow<IllegalStateException> { market.fetchTopTradingValueStocks() }.message shouldContain "no list"
        }

        @Test
        fun `오류 코드면 예외로 올린다`() {
            stub.respond(RANK, """{"return_code":3,"return_msg":"한도 초과"}""")

            shouldThrow<KiwoomApiException> { market.fetchTopTradingValueStocks() }.message shouldContain "한도 초과"
        }

        @Test
        fun `토큰 무효면 한 번 재시도한다`() {
            stub.server.stubFor(
                post(urlEqualTo(RANK)).inScenario("8005").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(okJson("""{"return_code":8005,"return_msg":"Token이 유효하지 않습니다"}""")).willSetStateTo("다시"),
            )
            stub.server.stubFor(
                post(urlEqualTo(RANK)).inScenario("8005").whenScenarioStateIs("다시")
                    .willReturn(okJson("""{"return_code":0,"trde_prica_upper":[${rankRow("005930", "삼성전자")}]}""")),
            )

            market.fetchTopTradingValueStocks()[0].stockCode shouldBe "005930"
        }

        @Test
        fun `요청 개수만큼만 자른다`() {
            val rows = (0 until 5).joinToString(",") { rankRow("00000$it", "종목$it", rank = "${it + 1}") }
            stub.respond(RANK, """{"return_code":0,"trde_prica_upper":[$rows]}""")

            market.fetchTopTradingValueStocks(count = 3) shouldHaveSize 3
        }
    }

    @Nested
    inner class 종목_기본정보 {
        private val detail = """{"stk_cd":"005930","stk_nm":"삼성전자","cur_prc":"+71500","flu_rt":"+1.5","mac":"4200000",
            "open_pric":"71000","base_pric":"70500","high_pric":"72000","low_pric":"70800","trde_qty":"987654"}"""

        @Test
        fun `접미사가 없으면 SOR 통합 코드를 붙인다`() {
            respondFor(STOCK_INFO, "005930_AL", detail)

            market.fetchStockDetail("005930")!!.accumulatedVolume shouldBe 987654
        }

        @Test
        fun `이미 접미사가 있으면 그대로 둔다`() {
            respondFor(STOCK_INFO, "005930_NX", detail)

            market.fetchStockDetail("005930_NX").shouldNotBeNull()
        }

        @Test
        fun `실패하면 null`() {
            stub.server.stubFor(post(urlEqualTo(STOCK_INFO)).willReturn(aResponse().withStatus(500)))

            market.fetchStockDetail("005930").shouldBeNull()
        }

        @Test
        fun `연결이 끊기면 null`() {
            stub.fail(STOCK_INFO)

            market.fetchStockDetail("005930").shouldBeNull()
        }
    }

    @Nested
    inner class 일봉 {
        private val base = LocalDate.of(2026, 9, 11)

        @Test
        fun `일봉은 SOR 접미사를 붙이지 않는다`() {
            respondFor(
                CHART, "005930",
                """{"stk_dt_pole_chart_qry":[{"dt":"20260911","cur_prc":"1","open_pric":"1","high_pric":"1","low_pric":"1","trde_qty":"1","pred_pre":"0"}]}""",
            )

            market.fetchDailyCandles("005930_AL", baseDate = base) shouldHaveSize 1
        }

        @Test
        fun `전일대비로 등락률을 역산한다`() {
            stub.respond(
                CHART,
                """{"stk_dt_pole_chart_qry":[{"dt":"20260911","open_pric":"70000","high_pric":"72000","low_pric":"69000",
                "cur_prc":"+71500","trde_qty":"1000","pred_pre":"+1500"}]}""",
            )

            val candle = market.fetchDailyCandles("005930", baseDate = base)[0]

            candle.closePrice shouldBe 71500
            candle.date shouldBe base
            candle.changeRate shouldBe 1500.0 / 70000 * 100
        }

        @Test
        fun `날짜가 비어있는 패딩 항목은 건너뛴다`() {
            stub.respond(
                CHART,
                """{"stk_dt_pole_chart_qry":[
                {"dt":"20260911","cur_prc":"71500","open_pric":"1","high_pric":"1","low_pric":"1","trde_qty":"1","pred_pre":"0"},
                {"dt":"","cur_prc":"","open_pric":"","high_pric":"","low_pric":"","trde_qty":"","pred_pre":""}]}""",
            )

            market.fetchDailyCandles("005930", baseDate = base) shouldHaveSize 1
        }

        @Test
        fun `개수만 다른 조회는 한 번의 호출을 나눠 쓴다`() {
            val rows = listOf(9, 8).flatMap { m -> (28 downTo 1).map { d -> "%02d%02d".format(m, d) } }.joinToString(",") {
                """{"dt":"2026$it","cur_prc":"1","open_pric":"1","high_pric":"1","low_pric":"1","trde_qty":"1","pred_pre":"0"}"""
            }
            stub.respond(CHART, """{"stk_dt_pole_chart_qry":[$rows]}""")
            val base = LocalDate.of(2026, 9, 28)

            val forFilter = market.fetchDailyCandles("005930", 3, base)
            val forChart = market.fetchDailyCandles("005930", 5, base)

            stub.calls(CHART) shouldBe 1
            forFilter shouldHaveSize 3
            forChart shouldHaveSize 5
            forChart.take(3) shouldBe forFilter
        }
    }

    @Nested
    inner class 분봉 {
        private val base = LocalDate.of(2026, 9, 11)
        private val bar = """{"stk_min_pole_chart_qry":[{"cntr_tm":"20260911093000","open_pric":"70000","high_pric":"72000",
            "low_pric":"69000","cur_prc":"-71500","trde_qty":"100"}]}"""

        @Test
        fun `분봉은 SOR 통합 코드로 부른다`() {
            respondFor(CHART, "005930_AL", bar)

            market.fetchHistoricalMinuteCandles("005930", base) shouldHaveSize 1
        }

        @Test
        fun `거래대금을 종가곱거래량으로 근사한다`() {
            stub.respond(CHART, bar)

            val candle = market.fetchHistoricalMinuteCandles("005930", base)[0]

            candle.closePrice shouldBe 71500
            candle.tradingValue shouldBe 71500L * 100
            (candle.dateTime.hour to candle.dateTime.minute) shouldBe (9 to 30)
        }

        @Test
        fun `실패하면 빈 목록`() {
            stub.server.stubFor(post(urlEqualTo(CHART)).willReturn(aResponse().withStatus(500)))

            market.fetchHistoricalMinuteCandles("005930", base).shouldBeEmpty()
        }
    }

    @Nested
    inner class 가격_파서 {
        @Test
        fun `부호는 등락 방향 표식이라 절대값으로 읽는다`() {
            mapOf("+71500" to 71500L, "-71500" to 71500L, "71500" to 71500L, "" to 0L, "abc" to 0L)
                .forEach { (input, expected) -> KiwoomMarket.parsePrice(input) shouldBe expected }
        }
    }

    @Nested
    inner class NXT_상장_여부 {
        @Test
        fun `NX로 조회해 종목명이 오면 상장`() {
            respondFor(STOCK_INFO, "000660_NX", """{"return_code":0,"stk_nm":"SK하이닉스","cur_prc":"+1834000"}""")

            market.fetchNxtListed("000660_AL") shouldBe true
        }

        @Test
        fun `정상 응답에 종목명이 비어 있으면 비상장`() {
            stub.respond(STOCK_INFO, """{"return_code":0,"return_msg":"정상적으로 처리되었습니다","stk_nm":"","cur_prc":""}""")

            market.fetchNxtListed("036010") shouldBe false
        }

        @Test
        fun `오류 코드면 단정하지 않는다`() {
            stub.respond(STOCK_INFO, """{"return_code":1,"return_msg":"오류"}""")

            market.fetchNxtListed("036010").shouldBeNull()
        }

        @Test
        fun `서버 오류면 단정하지 않는다`() {
            stub.server.stubFor(post(urlEqualTo(STOCK_INFO)).willReturn(aResponse().withStatus(500)))

            market.fetchNxtListed("036010").shouldBeNull()
        }
    }

    companion object {
        private const val RANK = "/api/dostk/rkinfo"
        private const val STOCK_INFO = "/api/dostk/stkinfo"
        private const val CHART = "/api/dostk/chart"
    }
}
