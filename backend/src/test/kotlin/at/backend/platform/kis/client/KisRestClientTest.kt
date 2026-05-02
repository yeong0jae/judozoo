package at.backend.platform.kis.client

import at.backend.stock.domain.StockInfo
import at.backend.trading.domain.execution.Execution
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.ZoneId

class KisRestClientTest : FunSpec({

    val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())

    beforeSpec { wireMock.start() }
    afterSpec { wireMock.stop() }
    beforeEach { wireMock.resetAll() }

    fun client() = KisRestClient(
        accountNo = "12345678",
        accountProductCode = "01",
        restClient = RestClient.builder()
            .baseUrl("http://localhost:${wireMock.port()}")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build(),
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

        test("3분봉 조회 - 체결 시각을 봉 종료 시각으로 매핑") {
            stub("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice", "bars.json")

            val bars = client().getBars("005930")

            bars shouldHaveSize 2
            bars[0].stockCode shouldBe "005930"
            bars[0].openPrice shouldBe 70100
            bars[0].closePrice shouldBe 70200
            val seoulEnd = bars[0].endTime.atZone(ZoneId.of("Asia/Seoul"))
            seoulEnd.hour shouldBe 15
            seoulEnd.minute shouldBe 3
        }

        test("일별 체결 조회 - 체결 수량 0인 주문은 제외하고 변환") {
            stub("/uapi/domestic-stock/v1/trading/inquire-daily-ccld", "daily-ccld.json")

            val executions = client().getDailyExecutions("005930", LocalDate.of(2026, 1, 2))

            executions shouldHaveSize 1
            executions[0] shouldBe Execution(executedPrice = 70000, executedQty = 10, fee = 0)
        }
    }
})
