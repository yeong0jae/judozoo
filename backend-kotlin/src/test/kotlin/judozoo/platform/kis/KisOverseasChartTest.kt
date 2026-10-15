package judozoo.platform.kis

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import judozoo.platform.kis.KisStub.Companion.ok
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

class KisOverseasChartTest {
    private val stub = KisStub()
    private val chart = KisOverseasChart(stub.client)

    @AfterEach
    fun 정리() = stub.close()

    private fun bar(tymd: String, xymd: String, xhms: String, kymd: String, khms: String, last: String = "100", evol: String = "1000", eamt: String = "99000") =
        """{"tymd":"$tymd","xymd":"$xymd","xhms":"$xhms","kymd":"$kymd","khms":"$khms","open":"99","high":"101","low":"98",
        "last":"$last","evol":"$evol","eamt":"$eamt"}"""

    private fun page(vararg bars: String) = """{"rt_cd":"0","msg1":"정상","output2":[${bars.joinToString(",")}]}"""

    private fun day(xymd: String) = """{"xymd":"$xymd","clos":"100","open":"99","high":"101","low":"98","tvol":"5000"}"""

    @Nested
    inner class 분봉_페이징 {
        /** 한국시각(kymd+khms)을 커서로 넣으면 엉뚱한 구간이 온다. */
        @Test
        fun `다음조회 커서는 현지시각에서 1분을 뺀 값이다`() {
            stub.respondInOrder(
                MINUTE,
                ok(page(bar("20260825", "20260825", "093000", "20260825", "223000"), bar("20260825", "20260825", "092900", "20260825", "222900"))),
                ok(page()),
            )

            chart.fetchMinuteCandles("NAS", "AAPL")

            val second = stub.requests(MINUTE)[1]
            second.queryParameter("KEYB").firstValue() shouldBe "20260825092800" // 현지 09:29에서 1분 뺀 값
            second.queryParameter("NEXT").firstValue() shouldBe "1"
        }

        @Test
        fun `첫 페이지에는 커서를 비워 보낸다`() {
            stub.respond(MINUTE, page())

            chart.fetchMinuteCandles("NAS", "AAPL")

            val first = stub.requests(MINUTE)[0]
            first.queryParameter("KEYB").firstValue() shouldBe ""
            first.queryParameter("NEXT").firstValue() shouldBe ""
        }

        /** SESSION_DAYS(2)를 넘는 거래일이 보이면 충분히 받은 것. */
        @Test
        fun `거래일이 기준을 넘으면 더 받지 않는다`() {
            stub.respondInOrder(
                MINUTE,
                ok(page(bar("20260825", "20260825", "093000", "20260825", "223000"))),
                ok(page(bar("20260824", "20260824", "093000", "20260824", "223000"))),
                ok(page(bar("20260823", "20260823", "093000", "20260823", "223000"))),
                ok(page(bar("20260822", "20260822", "093000", "20260822", "223000"))),
            )

            chart.fetchMinuteCandles("NAS", "AAPL")

            stub.calls(MINUTE) shouldBe 3 // 3번째에서 거래일 3개가 되어 중단
        }

        @Test
        fun `최신 두 거래일치만 남긴다`() {
            stub.respondInOrder(
                MINUTE,
                ok(page(bar("20260825", "20260825", "093000", "20260825", "223000", last = "1"))),
                ok(page(bar("20260824", "20260824", "093000", "20260824", "223000", last = "2"))),
                ok(page(bar("20260823", "20260823", "093000", "20260823", "223000", last = "3"))),
            )

            chart.fetchMinuteCandles("NAS", "AAPL").map { it.close }.sorted() shouldBe listOf(1.0, 2.0) // 20260823은 버려진다
        }

        @Test
        fun `빈 페이지가 오면 멈춘다`() {
            stub.respond(MINUTE, page())

            chart.fetchMinuteCandles("NAS", "AAPL").shouldBeEmpty()
            stub.calls(MINUTE) shouldBe 1
        }
    }

    @Nested
    inner class 분봉_변환 {
        @Test
        fun `봉의 시각은 한국기준을 쓴다`() {
            stub.respondInOrder(MINUTE, ok(page(bar("20260825", "20260825", "093000", "20260825", "223000"))), ok(page()))

            val candle = chart.fetchMinuteCandles("NAS", "AAPL")[0]

            (candle.dateTime.hour to candle.dateTime.minute) shouldBe (22 to 30) // 현지 09:30이 아니라 한국 22:30
        }

        @Test
        fun `시간이 6자리보다 짧으면 0을 채운다`() {
            stub.respondInOrder(MINUTE, ok(page(bar("20260825", "20260825", "93000", "20260825", "93000"))), ok(page()))

            val candle = chart.fetchMinuteCandles("NAS", "AAPL")[0]

            (candle.dateTime.hour to candle.dateTime.minute) shouldBe (9 to 30)
        }

        @Test
        fun `시각을 해석할 수 없는 봉은 버린다`() {
            stub.respondInOrder(
                MINUTE,
                ok(page(bar("20260825", "20260825", "093000", "이상", "223000"), bar("20260825", "20260825", "092900", "20260825", "222900"))),
                ok(page()),
            )

            chart.fetchMinuteCandles("NAS", "AAPL") shouldHaveSize 1
        }

        @Test
        fun `숫자가 아닌 값은 0으로 둔다`() {
            stub.respondInOrder(MINUTE, ok(page(bar("20260825", "20260825", "093000", "20260825", "223000", evol = "", eamt = "-"))), ok(page()))

            val candle = chart.fetchMinuteCandles("NAS", "AAPL")[0]

            (candle.volume to candle.tradingValue) shouldBe (0L to 0.0)
        }
    }

    @Nested
    inner class 분봉_실패 {
        /** 페이징 도중 조용히 잘리면 봉이 비는 걸 눈치채기 어렵다. */
        @Test
        fun `오류 코드가 오면 예외를 올린다`() {
            stub.respond(MINUTE, """{"rt_cd":"1","msg1":"조회 오류","output2":null}""")

            shouldThrow<IllegalStateException> { chart.fetchMinuteCandles("NAS", "AAPL") }.message shouldContain "해외 분봉 오류"
        }
    }

    @Nested
    inner class 분봉_이어_받기 {
        @Test
        fun `기준 시각 이후 봉만 주고 그보다 이른 봉이 보이면 멈춘다`() {
            stub.respondInOrder(
                MINUTE,
                ok(
                    page(
                        bar("20260825", "20260825", "093200", "20260825", "223200"),
                        bar("20260825", "20260825", "093100", "20260825", "223100"),
                        bar("20260825", "20260825", "093000", "20260825", "223000"),
                    ),
                ),
                ok(page(bar("20260825", "20260825", "092900", "20260825", "222900"))),
            )

            val candles = chart.fetchMinuteCandles("NAS", "AAPL", since = LocalDateTime.of(2026, 8, 25, 22, 31))

            stub.calls(MINUTE) shouldBe 1
            candles.map { it.dateTime.minute }.sorted() shouldBe listOf(31, 32)
        }
    }

    @Nested
    inner class 분봉_중간에_끊기면 {
        @Test
        fun `뒷페이지가 실패하면 받은 데까지를 담아 알린다`() {
            stub.respondInOrder(
                MINUTE,
                ok(page(bar("20260825", "20260825", "093000", "20260825", "223000"))),
                500 to """{"rt_cd":"1","msg_cd":"EGW00123","msg1":"서버 오류"}""",
            )

            val interrupted = shouldThrow<KisOverseasChart.MinutePagesInterrupted> { chart.fetchMinuteCandles("NAS", "AAPL") }

            interrupted.candles.map { it.dateTime.minute } shouldBe listOf(30)
        }

        /** KIS는 한도 초과도 HTTP 500으로 준다 — 상태 코드만으로는 무엇인지 모른다. */
        @Test
        fun `오류 본문의 코드와 메시지를 싣는다`() {
            stub.respond(MINUTE, """{"rt_cd":"1","msg_cd":"EGW00123","msg1":"서버 오류"}""", status = 500)

            shouldThrow<IllegalStateException> { chart.fetchMinuteCandles("NAS", "AAPL") }.message shouldContain "HTTP 500 EGW00123 서버 오류"
        }

        @Test
        fun `한도 초과면 잠깐 쉬고 한 번 더 부른다`() {
            stub.respondInOrder(
                MINUTE,
                500 to """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""",
                ok(page(bar("20260825", "20260825", "093000", "20260825", "223000"))),
                ok(page()),
            )

            val candles = chart.fetchMinuteCandles("NAS", "AAPL")

            stub.pauses shouldBe listOf(Duration.ofSeconds(1))
            stub.calls(MINUTE) shouldBe 3
            candles shouldHaveSize 1
        }

        @Test
        fun `재시도도 한도 초과면 포기한다`() {
            stub.respond(MINUTE, """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초과"}""", status = 500)

            shouldThrow<IllegalStateException> { chart.fetchMinuteCandles("NAS", "AAPL") }.message shouldContain "EGW00201"
            stub.calls(MINUTE) shouldBe 2
        }
    }

    @Nested
    inner class 일봉 {
        @Test
        fun `오늘부터 한 페이지만 받는다`() {
            stub.respond(DAILY, page(*Array(100) { day("20260825") }))

            chart.fetchDailyCandles("NAS", "AAPL") shouldHaveSize 100
            stub.calls(DAILY) shouldBe 1
            stub.requests(DAILY)[0].queryParameter("BYMD").firstValue() shouldBe ""
        }

        /** 일봉은 없으면 차트만 짧아지고 끝이라 호출측을 깨우지 않는다. */
        @Test
        fun `오류 코드가 오면 빈 목록을 준다`() {
            stub.respond(DAILY, """{"rt_cd":"1","msg1":"오류","output2":null}""")

            chart.fetchDailyCandles("NAS", "AAPL").shouldBeEmpty()
        }

        @Test
        fun `HTTP 오류에도 빈 목록을 준다`() {
            stub.respondText(DAILY, "", status = 500)

            chart.fetchDailyCandles("NAS", "AAPL").shouldBeEmpty()
        }
    }

    @Nested
    inner class 요청_형식 {
        /** 장 초반에도 직전 세션 봉으로 채우기 위해서다. */
        @Test
        fun `분봉은 전일포함으로 요청한다`() {
            stub.respond(MINUTE, page())

            chart.fetchMinuteCandles("NAS", "AAPL")

            stub.server.verify(
                getRequestedFor(urlPathEqualTo(MINUTE))
                    .withQueryParam("PINC", equalTo("1")).withQueryParam("NMIN", equalTo("1")).withQueryParam("NREC", equalTo("120"))
                    .withHeader("tr_id", equalTo("HHDFS76950200")),
            )
        }

        @Test
        fun `일봉은 수정주가를 반영해 요청한다`() {
            stub.respond(DAILY, page())

            chart.fetchDailyCandles("NAS", "AAPL")

            stub.server.verify(
                getRequestedFor(urlPathEqualTo(DAILY))
                    .withQueryParam("MODP", equalTo("1")).withQueryParam("GUBN", equalTo("0"))
                    .withHeader("tr_id", equalTo("HHDFS76240000")),
            )
        }
    }

    companion object {
        private const val MINUTE = "/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice"
        private const val DAILY = "/uapi/overseas-price/v1/quotations/dailyprice"
    }
}
