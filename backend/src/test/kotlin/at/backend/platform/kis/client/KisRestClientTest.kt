package at.backend.platform.kis.client

import at.backend.common.test.TestRestClientConfig
import at.backend.platform.kis.KisRateLimiter
import at.backend.platform.kis.config.KisProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.web.client.RestClientException
import java.time.LocalDate
import kotlin.system.measureTimeMillis

class KisRestClientTest : FunSpec({

    val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())

    beforeSpec { wireMock.start() }
    afterSpec { wireMock.stop() }
    beforeEach { wireMock.resetAll() }

    fun client() = KisRestClient(
        accountNo = "12345678",
        accountProductCode = "01",
        restClient = TestRestClientConfig.restClient(wireMock),
    )

    fun fixture(name: String): String =
        KisRestClientTest::class.java.classLoader.getResourceAsStream("wiremock/$name")!!
            .bufferedReader().use { it.readText() }

    fun stub(urlPath: String, fixtureName: String) {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo(urlPath))
                .willReturn(
                    WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture(fixtureName))
                )
        )
    }

    fun stubPost(urlPath: String, fixtureName: String) {
        wireMock.stubFor(
            WireMock.post(WireMock.urlPathEqualTo(urlPath))
                .willReturn(
                    WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture(fixtureName))
                )
        )
    }

    context("정상 응답 역직렬화") {

        test("현재가 조회 - stck_prpr 필드 반환") {
            stub("/uapi/domestic-stock/v1/quotations/inquire-price", "current-price.json")

            client().getCurrentPrice("005930").output.stckPrpr shouldBe "70000"
        }

        test("개장일 조회 - opnd_yn 반환 (실 KIS는 output을 Array로 반환)") {
            stub("/uapi/domestic-stock/v1/quotations/chk-holiday", "holiday.json")

            client().checkHoliday(LocalDate.of(2026, 1, 2)).output.first().opndYn shouldBe "Y"
        }

        test("종목 검색 - output 단일 객체 반환") {
            stub("/uapi/domestic-stock/v1/quotations/search-stock-info", "stock-search.json")

            val output = client().searchStock("005930").output
            output.pdno shouldBe "005930"
            output.prdtAbrvName shouldBe "삼성전자"
        }

        test("예수금 조회 - prvs_rcdl_excc_amt 필드 반환") {
            stub("/uapi/domestic-stock/v1/trading/inquire-balance", "balance.json")

            client().getBalance().output2.first().prvsRcdlExccAmt shouldBe "1234567"
        }

        test("3분봉 조회 - output2 목록 반환") {
            stub("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice", "bars.json")

            val output = client().getBars("005930").output2
            output shouldHaveSize 2
            output[0].stckBsopDate shouldBe "20260102"
            output[0].stckCntgHour shouldBe "150300"
            output[0].stckOprc shouldBe "70100"
            output[0].stckPrpr shouldBe "70200"
        }

        test("일별 체결 조회 - output1 전체 목록 반환") {
            stub("/uapi/domestic-stock/v1/trading/inquire-daily-ccld", "daily-ccld.json")

            val output = client().getDailyExecutions("005930", LocalDate.of(2026, 1, 2)).output1
            output shouldHaveSize 3
            output[0].odno shouldBe "0000000001"
            output[0].totCcldQty shouldBe "10"
            output[0].avgPrvs shouldBe "70000"
        }

        test("주문 발송 - 매수 시 ODNO 반환") {
            stubPost("/uapi/domestic-stock/v1/trading/order-cash", "order-cash.json")

            val response = client().submitOrder("005930", "BUY", 10)
            response.output!!.odno shouldBe "0000123456"
            response.output!!.krxFwdgOrdOrgno shouldBe "00950"

            wireMock.verify(
                WireMock.postRequestedFor(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                    .withHeader("tr_id", WireMock.equalTo("TTTC0012U"))
                    .withHeader("custtype", WireMock.equalTo("P"))
                    .withRequestBody(WireMock.matchingJsonPath("$.PDNO", WireMock.equalTo("005930")))
                    .withRequestBody(WireMock.matchingJsonPath("$.ORD_DVSN", WireMock.equalTo("01")))
                    .withRequestBody(WireMock.matchingJsonPath("$.ORD_QTY", WireMock.equalTo("10")))
                    .withRequestBody(WireMock.matchingJsonPath("$.ORD_UNPR", WireMock.equalTo("0")))
            )
        }

        test("주문 발송 - 매도 시 매도 TR_ID 사용") {
            stubPost("/uapi/domestic-stock/v1/trading/order-cash", "order-cash.json")

            client().submitOrder("005930", "SELL", 5)

            wireMock.verify(
                WireMock.postRequestedFor(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                    .withHeader("tr_id", WireMock.equalTo("TTTC0011U"))
            )
        }

        test("주문 취소 - 잔량 전부 취소 페이로드 전송 + ODNO 반환") {
            stubPost("/uapi/domestic-stock/v1/trading/order-rvsecncl", "order-rvsecncl.json")

            val response = client().cancelRemainder(krxFwdgOrdOrgno = "00950", originalOdno = "0000123456")
            response.output!!.odno shouldBe "0000123457"

            wireMock.verify(
                WireMock.postRequestedFor(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                    .withHeader("tr_id", WireMock.equalTo("TTTC0013U"))
                    .withRequestBody(WireMock.matchingJsonPath("$.RVSE_CNCL_DVSN_CD", WireMock.equalTo("02")))
                    .withRequestBody(WireMock.matchingJsonPath("$.QTY_ALL_ORD_YN", WireMock.equalTo("Y")))
                    .withRequestBody(WireMock.matchingJsonPath("$.ORGN_ODNO", WireMock.equalTo("0000123456")))
                    .withRequestBody(WireMock.matchingJsonPath("$.KRX_FWDG_ORD_ORGNO", WireMock.equalTo("00950")))
            )
        }
    }

    context("4xx 응답") {

        test("잘못된 종목코드로 400을 받으면 RestClient 예외가 그대로 전파") {
            wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
                    .willReturn(WireMock.aResponse().withStatus(400))
            )

            shouldThrow<RestClientException> { client().getCurrentPrice("BAD_CODE") }
        }

        test("주문 발송 4xx 응답도 RestClient 예외가 전파된다") {
            wireMock.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                    .willReturn(WireMock.aResponse().withStatus(400))
            )

            shouldThrow<RestClientException> { client().submitOrder("005930", "BUY", 1) }
        }

        test("주문 발송 200이지만 rt_cd≠0(거부) 시 IllegalStateException으로 변환되어 msg1 포함") {
            wireMock.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                    .willReturn(
                        WireMock.aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""")
                    )
            )

            val ex = shouldThrow<IllegalStateException> { client().submitOrder("005930", "BUY", 1) }
            ex.message!! shouldContain "EGW00201"
            ex.message!! shouldContain "초당 거래건수"
        }

        test("주문 취소 200이지만 rt_cd≠0(거부) 시 IllegalStateException으로 변환된다") {
            wireMock.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                    .willReturn(
                        WireMock.aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"rt_cd":"1","msg_cd":"40050000","msg1":"이미 체결된 주문입니다."}""")
                    )
            )

            val ex = shouldThrow<IllegalStateException> {
                client().cancelRemainder(krxFwdgOrdOrgno = "00950", originalOdno = "0000123456")
            }
            ex.message!! shouldContain "40050000"
        }
    }

    context("5xx 응답") {

        test("KIS 서버 오류로 500을 받으면 RestClient 예외가 그대로 전파") {
            wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
                    .willReturn(WireMock.aResponse().withStatus(500))
            )

            shouldThrow<RestClientException> { client().getCurrentPrice("005930") }
        }
    }

    context("타임아웃") {

        test("응답 지연이 read-timeout(1초)을 초과하면 예외가 그대로 전파") {
            wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
                    .willReturn(
                        WireMock.aResponse()
                            .withFixedDelay(3000)
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(fixture("current-price.json"))
                    )
            )

            shouldThrow<RestClientException> { client().getCurrentPrice("005930") }
        }
    }

    context("Rate limit") {

        test("초당 20건 제한을 넘는 21번째 호출도 누락 없이 순차 처리") {
            stub("/uapi/domestic-stock/v1/quotations/inquire-price", "current-price.json")

            val rateLimiter = KisRateLimiter(
                KisProperties(
                    appKey = "k",
                    appSecret = "s",
                    accountNo = "12345678",
                    accountProductCode = "01",
                    baseUrl = "http://localhost",
                    wsUrl = "ws://localhost",
                    rateLimitPerSecond = 20,
                )
            )
            val rateLimitedClient = KisRestClient(
                accountNo = "12345678",
                accountProductCode = "01",
                restClient = TestRestClientConfig.builder(wireMock)
                    .requestInterceptor { request, body, execution ->
                        rateLimiter.acquire()
                        execution.execute(request, body)
                    }
                    .build(),
            )

            val elapsed = measureTimeMillis {
                repeat(21) { rateLimitedClient.getCurrentPrice("005930") }
            }

            elapsed shouldBeGreaterThanOrEqual 950L
            wireMock.verify(
                21,
                WireMock.getRequestedFor(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
            )
        }
    }
})
