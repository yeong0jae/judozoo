package at.backend.platform.kis.client

import at.backend.common.test.TestRestClientConfig
import at.backend.platform.kis.KisRateLimiter
import at.backend.platform.kis.config.KisProperties
import at.backend.stock.domain.StockInfo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.web.client.RestClientException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.system.measureTimeMillis

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

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

    context("정상 응답 매핑") {

        test("현재가 조회 - 응답 본문의 현재가를 정수로 변환") {
            stub("/uapi/domestic-stock/v1/quotations/inquire-price", "current-price.json")

            client().getCurrentPrice("005930") shouldBe 70000
        }

        test("영업일 조회 - 영업일 플래그가 Y면 true 반환") {
            stub("/uapi/domestic-stock/v1/quotations/chk-holiday", "holiday.json")

            client().isBusinessDay(LocalDate.of(2026, 1, 2)) shouldBe true
        }

        test("종목 검색 - 검색 결과를 종목 정보 도메인으로 변환") {
            stub("/uapi/domestic-stock/v1/quotations/search-stock-info", "stock-search.json")

            client().searchStock("삼성") shouldBe listOf(StockInfo("005930", "삼성전자"))
        }

        test("예수금 조회 - 주문 가능 금액을 Long으로 변환") {
            stub("/uapi/domestic-stock/v1/trading/inquire-balance", "balance.json")

            client().getBalance() shouldBe 1234567L
        }

        test("3분봉 조회 - 체결 시각을 봉 종료 시각으로 매핑하고 시작 시각은 3분 전") {
            stub("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice", "bars.json")

            val bars = client().getBars("005930")

            bars shouldHaveSize 2
            bars[0].stockCode shouldBe "005930"
            bars[0].openPrice shouldBe 70100
            bars[0].closePrice shouldBe 70200
            val firstEnd = bars[0].endTime.atZone(SEOUL)
            firstEnd.toLocalDate() shouldBe LocalDate.of(2026, 1, 2)
            firstEnd.toLocalTime() shouldBe LocalTime.of(15, 3, 0)
            bars[0].startTime shouldBe bars[0].endTime.minusSeconds(180)

            val secondEnd = bars[1].endTime.atZone(SEOUL)
            secondEnd.toLocalDate() shouldBe LocalDate.of(2026, 1, 2)
            secondEnd.toLocalTime() shouldBe LocalTime.of(15, 0, 0)
        }

        test("일별 체결 조회 - 다른 종목/체결 수량 0인 주문은 제외하고 변환") {
            stub("/uapi/domestic-stock/v1/trading/inquire-daily-ccld", "daily-ccld.json")

            val executions = client().getDailyExecutions("005930", LocalDate.of(2026, 1, 2))

            executions shouldHaveSize 1
            executions[0] shouldBe KisDailyFill(kisOrderNo = "0000000001", executedPrice = 70000, executedQty = 10)
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
