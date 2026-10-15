package judozoo.platform.kis

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class KisOverseasProductTest {
    private val stub = KisStub()
    private var now: Instant = Instant.parse("2026-09-29T05:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
        override fun instant(): Instant = now
    }
    private val product = KisOverseasProduct(stub.client, clock)

    @AfterEach
    fun 정리() = stub.close()

    private val aapl = """{"rt_cd":"0","msg1":"","output":{"lstg_stck_num":"1000000","ovrs_now_pric1":"230.5"}}"""

    @Nested
    inner class 시가총액 {
        @Test
        fun `한국 날짜가 먼저 바뀌어도 미국 날짜를 기준으로 삼는다`() {
            // KST 2026-09-30 00:30 = 뉴욕 09-29 오전
            KisOverseasProduct.usDate(Instant.parse("2026-09-29T15:30:00Z")) shouldBe LocalDate.of(2026, 9, 29)
            // KST 2026-09-30 13:30 = 뉴욕 09-30 00:30
            KisOverseasProduct.usDate(Instant.parse("2026-09-30T04:30:00Z")) shouldBe LocalDate.of(2026, 9, 30)
        }

        @Test
        fun `같은 미국 날짜에는 한 번만 조회하고 날짜가 바뀌면 갱신한다`() {
            stub.respond(URL, aapl)

            product.fetchMarketCap("NAS", "AAPL") shouldBe 230_500_000
            product.fetchMarketCap("NAS", "AAPL") shouldBe 230_500_000
            stub.calls(URL) shouldBe 1

            now += Duration.ofDays(1)
            product.fetchMarketCap("NAS", "AAPL") shouldBe 230_500_000
            stub.calls(URL) shouldBe 2
        }

        @Test
        fun `상장주식수와 현재가를 곱한다`() {
            stub.respond(URL, aapl)

            product.fetchMarketCap("NAS", "AAPL") shouldBe 230_500_000
        }

        @Test
        fun `거래소마다 상품유형코드가 다르다`() {
            listOf("NAS" to "512", "NYS" to "513", "AMS" to "529").forEach { (excd, code) ->
                stub.server.stubFor(get(urlPathEqualTo(URL)).withQueryParam("PRDT_TYPE_CD", equalTo(code)).willReturn(okJson(aapl)))

                product.fetchMarketCap(excd, "AAPL") shouldBe 230_500_000
            }
        }

        @Test
        fun `모르는 거래소는 호출 전에 막는다`() {
            shouldThrow<IllegalArgumentException> { product.fetchMarketCap("XXX", "AAPL") }.message shouldContain "지원하지 않는 거래소"
            stub.calls(URL) shouldBe 0
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                """{"lstg_stck_num":"0","ovrs_now_pric1":"230.5"}""",
                """{"lstg_stck_num":"1000","ovrs_now_pric1":"0"}""",
                """{"lstg_stck_num":"","ovrs_now_pric1":"230.5"}""",
                "null",
            ],
        )
        fun `값이 온전하지 않으면 시가총액을 주지 않는다`(output: String) {
            stub.respond(URL, """{"rt_cd":"0","msg1":"","output":$output}""")

            product.fetchMarketCap("NAS", "AAPL").shouldBeNull()
        }

        @Test
        fun `결과가 없으면 캐시하지 않아 다음에 다시 시도한다`() {
            stub.respond(URL, """{"rt_cd":"0","msg1":"","output":null}""")

            product.fetchMarketCap("NAS", "AAPL")
            product.fetchMarketCap("NAS", "AAPL")

            stub.calls(URL) shouldBe 2
        }

        /** 상세 화면 전체가 500이 되지 않게 — 시가총액만 "조회 불가"로 남는다. */
        @Test
        fun `KIS가 500을 주면 예외 없이 시가총액을 주지 않는다`() {
            stub.respond(URL, """{"rt_cd":"1","msg_cd":"EGW00123","msg1":"기간이 만료된 token"}""", status = 500)

            product.fetchMarketCap("NAS", "META").shouldBeNull()
        }

        @Test
        fun `본문이 JSON이 아니어도 예외 없이 시가총액을 주지 않는다`() {
            stub.respondText(URL, "Internal Server Error", status = 500)

            product.fetchMarketCap("NAS", "META").shouldBeNull()
        }

        @Test
        fun `한도 초과면 1초 쉬고 한 번 더 부른다`() {
            stub.respondInOrder(
                URL,
                500 to """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""",
                200 to aapl,
            )

            product.fetchMarketCap("NAS", "META") shouldBe 230_500_000
            stub.calls(URL) shouldBe 2
            stub.pauses shouldBe listOf(Duration.ofSeconds(1))
        }

        @Test
        fun `다시 불러도 한도 초과면 시가총액을 주지 않는다`() {
            stub.respond(URL, """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""", status = 500)

            product.fetchMarketCap("NAS", "META").shouldBeNull()
            stub.calls(URL) shouldBe 2
        }
    }

    companion object {
        private const val URL = "/uapi/overseas-price/v1/quotations/search-info"
    }
}
