package at.backend.trading.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import at.backend.trading.domain.AlreadyClosedException
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.TradingValidationException
import at.backend.trading.domain.TradingValidationException.ErrorCode
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import at.backend.library.exception.EntityNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class)
class TradingServiceTest(
    @Autowired private val tradingService: TradingService,
    @Autowired private val tradingCycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
) : IntegrationTestBase() {

    private fun stubSearchStock(name: String = "삼성전자") {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
            output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = name))
        )
    }

    private fun stubSearchStockEmpty() {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(output = emptyList())
    }

    private fun stubCurrentPrice(price: String = "70000") {
        every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
            output = KisCurrentPriceResponse.Output(stckPrpr = price)
        )
    }

    private fun stubBalance(amount: String = "1000000") {
        every { kisRestClient.getBalance() } returns KisBalanceResponse(
            output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = amount))
        )
    }

    private fun stubHoliday(bzdyYn: String = "Y") {
        every { kisRestClient.checkHoliday(any()) } returns KisHolidayResponse(
            output = listOf(KisHolidayResponse.Output(bzdyYn = bzdyYn))
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

    private fun saveCycle(
        stockCode: String = "005930",
        stockName: String = "삼성전자",
        perBuyAmount: Long = 100_000L,
        status: TradingCycleStatus = TradingCycleStatus.INITIATED,
    ) = tradingCycleRepository.save(
        TradingCycle(
            stockCode = stockCode,
            stockName = stockName,
            perBuyAmount = perBuyAmount,
            buyIntervalMin = 3,
            splitSellRatio = BigDecimal("0.5"),
            midwayProfitPct = BigDecimal("1.5"),
            breakevenThresholdPct = BigDecimal("0.5"),
            stopLossPct = BigDecimal("-0.02"),
            status = status,
        )
    )

    init {
        beforeEach {
            clearMocks(kisRestClient)
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
                stubBalance("1000000")
                saveCycle(stockCode = "035420", stockName = "NAVER", perBuyAmount = 900_000L)

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput(stockCode = "005930", perBuyAmount = 200_000L))
                }
                ex.errorCode shouldBe ErrorCode.INSUFFICIENT_BALANCE
            }
        }

        context("동일 종목 중복 접수") {
            test("같은 종목의 활성 사이클이 있으면 DUPLICATE_COMMAND") {
                saveCycle(stockCode = "005930")

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput())
                }
                ex.errorCode shouldBe ErrorCode.DUPLICATE_COMMAND
            }
        }

        context("취소") {
            test("INITIATED 사이클 취소 시 LIQUIDATING으로 전환된다") {
                val cycle = saveCycle(status = TradingCycleStatus.INITIATED)

                tradingService.cancel(cycle.id)

                tradingCycleRepository.findById(cycle.id).orElseThrow().status shouldBe TradingCycleStatus.LIQUIDATING
            }

            test("BUYING 사이클 취소 시 LIQUIDATING으로 전환된다") {
                val cycle = saveCycle(status = TradingCycleStatus.BUYING)

                tradingService.cancel(cycle.id)

                tradingCycleRepository.findById(cycle.id).orElseThrow().status shouldBe TradingCycleStatus.LIQUIDATING
            }

            test("HOLDING 사이클 취소 시 LIQUIDATING으로 전환된다") {
                val cycle = saveCycle(status = TradingCycleStatus.HOLDING)

                tradingService.cancel(cycle.id)

                tradingCycleRepository.findById(cycle.id).orElseThrow().status shouldBe TradingCycleStatus.LIQUIDATING
            }

            test("LIQUIDATING 사이클 취소는 멱등으로 LIQUIDATING 유지") {
                val cycle = saveCycle(status = TradingCycleStatus.LIQUIDATING)

                tradingService.cancel(cycle.id)

                tradingCycleRepository.findById(cycle.id).orElseThrow().status shouldBe TradingCycleStatus.LIQUIDATING
            }

            test("CLOSED 사이클 취소 시 AlreadyClosedException 발생") {
                val cycle = saveCycle(status = TradingCycleStatus.CLOSED)

                shouldThrow<AlreadyClosedException> { tradingService.cancel(cycle.id) }
            }

            test("없는 id 취소 시 EntityNotFoundException 발생") {
                shouldThrow<EntityNotFoundException> { tradingService.cancel(99L) }
            }
        }
    }
}
