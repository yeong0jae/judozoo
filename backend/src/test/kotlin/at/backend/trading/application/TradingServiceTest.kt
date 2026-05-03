package at.backend.trading.application

import at.backend.common.test.IntegrationTestBase
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.TradingValidationException
import at.backend.trading.domain.TradingValidationException.ErrorCode
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal

class TradingServiceTest(
    @Autowired private val tradingService: TradingService,
    @Autowired private val tradingCycleRepository: TradingCycleJpaRepository,
) : IntegrationTestBase() {

    companion object {
        private val wireMock = WireMockServer(options().dynamicPort()).also { server ->
            server.start()
            server.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/oauth2/tokenP"))
                    .willReturn(
                        WireMock.aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"access_token":"test-token","access_token_token_expired":"2099-01-01 00:00:00"}""")
                    )
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun kisProperties(registry: DynamicPropertyRegistry) {
            registry.add("kis.base-url") { "http://localhost:${wireMock.port()}" }
        }
    }

    private fun stubSearchStock(name: String = "삼성전자") {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/search-stock-info"))
                .willReturn(WireMock.aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"output":[{"pdno":"005930","prdt_abrv_name":"$name"}]}"""))
        )
    }

    private fun stubSearchStockEmpty() {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/search-stock-info"))
                .willReturn(WireMock.aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"output":[]}"""))
        )
    }

    private fun stubCurrentPrice(price: String = "70000") {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
                .willReturn(WireMock.aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"output":{"stck_prpr":"$price"}}"""))
        )
    }

    private fun stubBalance(amount: String = "1000000") {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
                .willReturn(WireMock.aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"output2":[{"prvs_rcdl_excc_amt":"$amount"}]}"""))
        )
    }

    private fun stubHoliday(bzdyYn: String = "Y") {
        wireMock.stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/uapi/domestic-stock/v1/quotations/chk-holiday"))
                .willReturn(WireMock.aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"output":[{"bzdy_yn":"$bzdyYn"}]}"""))
        )
    }

    private fun stubAllKisSuccess() {
        stubSearchStock()
        stubCurrentPrice()
        stubBalance()
        stubHoliday()
    }

    private fun validInput(
        stockCode: String = "005930",
        perBuyAmount: Long = 100_000L,
    ) = TradingInput(
        stockCode = stockCode,
        perBuyAmount = perBuyAmount,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.5"),
        midwayProfitPct = BigDecimal("1.5"),
        breakevenThresholdPct = BigDecimal("0.5"),
        stopLossPct = BigDecimal("2.0"),
    )

    init {
        beforeEach {
            wireMock.resetAll()
            tradingCycleRepository.deleteAll()
            stubAllKisSuccess()
        }

        context("입력값 범위 오류") {
            test("perBuyAmount가 0이면 INVALID_PARAMETER") {
                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput(perBuyAmount = 0))
                }
                ex.errorCode shouldBe ErrorCode.INVALID_PARAMETER
            }
        }

        context("종목 미존재") {
            test("KIS 검색 결과가 없으면 STOCK_NOT_FOUND") {
                stubSearchStockEmpty()

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput())
                }
                ex.errorCode shouldBe ErrorCode.STOCK_NOT_FOUND
            }
        }

        context("1주 가격 초과") {
            test("현재가가 perBuyAmount를 초과하면 PRICE_BELOW_ONE_SHARE") {
                stubCurrentPrice("200000")

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput(perBuyAmount = 100_000L))
                }
                ex.errorCode shouldBe ErrorCode.PRICE_BELOW_ONE_SHARE
            }
        }

        context("잔고 부족") {
            test("INITIATED 사이클 예약금 차감 후 잔고가 부족하면 INSUFFICIENT_BALANCE") {
                stubBalance("100000")

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput(perBuyAmount = 200_000L))
                }
                ex.errorCode shouldBe ErrorCode.INSUFFICIENT_BALANCE
            }

            test("다중 종목 동시 접수 시 INITIATED 사이클 예약금이 누적 차감됨") {
                // 잔고 1,000,000원 / 기존 INITIATED 사이클이 900,000원 점유
                stubBalance("1000000")
                val existingCycle = at.backend.trading.domain.cycle.TradingCycle(
                    stockCode = "035420",
                    stockName = "NAVER",
                    perBuyAmount = 900_000L,
                    buyIntervalMin = 3,
                    splitSellRatio = BigDecimal("0.5"),
                    midwayProfitPct = BigDecimal("1.5"),
                    breakevenThresholdPct = BigDecimal("0.5"),
                    stopLossPct = BigDecimal("-0.02"),
                )
                tradingCycleRepository.save(existingCycle)

                // 200,000원 접수 시 잔고 1,000,000 - 900,000 = 100,000 < 200,000 → 거부
                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput(stockCode = "005930", perBuyAmount = 200_000L))
                }
                ex.errorCode shouldBe ErrorCode.INSUFFICIENT_BALANCE
            }
        }

        context("동일 종목 중복 접수") {
            test("같은 종목의 활성 사이클이 있으면 DUPLICATE_COMMAND") {
                val existingCycle = at.backend.trading.domain.cycle.TradingCycle(
                    stockCode = "005930",
                    stockName = "삼성전자",
                    perBuyAmount = 100_000L,
                    buyIntervalMin = 3,
                    splitSellRatio = BigDecimal("0.5"),
                    midwayProfitPct = BigDecimal("1.5"),
                    breakevenThresholdPct = BigDecimal("0.5"),
                    stopLossPct = BigDecimal("-0.02"),
                )
                tradingCycleRepository.save(existingCycle)

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput())
                }
                ex.errorCode shouldBe ErrorCode.DUPLICATE_COMMAND
            }
        }

        context("정상 접수") {
            // 장 운영 시간(09:00~15:14, buyIntervalMin=3 기준) 내에서만 통과
            test("모든 조건 충족 시 TradingCycle이 INITIATED 상태로 저장됨") {
                val result = tradingService.create(validInput())

                val saved = tradingCycleRepository.findById(result.id).orElseThrow()
                saved.stockCode shouldBe "005930"
                saved.stockName shouldBe "삼성전자"
                saved.status shouldBe TradingCycleStatus.INITIATED
                saved.perBuyAmount shouldBe 100_000L
            }
        }
    }
}
