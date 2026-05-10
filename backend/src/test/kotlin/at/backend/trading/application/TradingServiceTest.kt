package at.backend.trading.application

import at.backend.common.test.CycleOrchestratorMockConfig
import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.library.exception.EntityNotFoundException
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class, CycleOrchestratorMockConfig::class)
class TradingServiceTest(
    @Autowired private val tradingService: TradingService,
    @Autowired private val tradingCycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
    @Autowired private val cycleOrchestrator: at.backend.trading.application.CycleOrchestrator,
) : IntegrationTestBase() {

    private fun stubSearchStock(name: String = "삼성전자") {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
            output = KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = name)
        )
    }

    private fun stubSearchStockEmpty() {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
            output = KisStockSearchResponse.Output(pdno = "999999", prdtAbrvName = ""),
        )
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

    private fun stubHoliday(opndYn: String = "Y") {
        every { kisRestClient.checkHoliday(any()) } returns KisHolidayResponse(
            output = listOf(KisHolidayResponse.Output(opndYn = opndYn))
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
            accountNo = "00000000",
            stockCode = stockCode,
            stockName = stockName,
            perBuyAmount = perBuyAmount,
            buyIntervalMin = 3,
            splitSellRatio = BigDecimal("0.5"),
            midwayProfitPct = BigDecimal("1.5"),
            breakevenThresholdPct = BigDecimal("0.5"),
            stopLossPct = BigDecimal("-2.0"),
            status = status,
        )
    )

    init {
        beforeEach {
            clearMocks(kisRestClient)
            clearMocks(cycleOrchestrator)
            tradingCycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
            stubAllKisSuccess()
        }

        context("정상 생성") {
            test("유효한 입력이면 INITIATED 상태의 사이클이 입력값 그대로 저장된다") {
                val result = tradingService.create(validInput())

                val saved = tradingCycleRepository.findById(result.id).orElseThrow()
                saved.status shouldBe TradingCycleStatus.INITIATED
                saved.stockCode shouldBe "005930"
                saved.stockName shouldBe "삼성전자"
                saved.perBuyAmount shouldBe 100_000L
                saved.buyIntervalMin shouldBe 3
                saved.splitSellRatio shouldBe BigDecimal("0.500")
                saved.midwayProfitPct shouldBe BigDecimal("1.500")
                saved.breakevenThresholdPct shouldBe BigDecimal("0.500")
            }

            test("손절 비율은 음수로 저장된다") {
                val result = tradingService.create(validInput())

                val saved = tradingCycleRepository.findById(result.id).orElseThrow()
                saved.stopLossPct shouldBe BigDecimal("-2.000")
            }
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
            test("perBuyAmount × MAX_BUY_ATTEMPT(3)이 잔고를 초과하면 INSUFFICIENT_BALANCE") {
                stubBalance("100000")

                val ex = shouldThrow<TradingValidationException> {
                    tradingService.create(validInput(perBuyAmount = 200_000L))
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

        context("오케스트레이터 연동") {
            test("정상 생성 시 트랜잭션 커밋 후 사이클 실행이 시작된다") {
                val result = tradingService.create(validInput())

                verify(exactly = 1) {
                    cycleOrchestrator.start(match { it.id == result.id && it.stockCode == "005930" })
                }
            }

            test("검증 실패 시 사이클 실행이 시작되지 않는다") {
                stubSearchStockEmpty()

                shouldThrow<TradingValidationException> { tradingService.create(validInput()) }

                verify(exactly = 0) { cycleOrchestrator.start(any()) }
            }

            test("취소 시 트랜잭션 커밋 후 오케스트레이터 취소가 호출된다") {
                val cycle = saveCycle(status = TradingCycleStatus.HOLDING)

                tradingService.cancel(cycle.id)

                verify(exactly = 1) { cycleOrchestrator.cancel(cycle.id) }
            }

            test("취소 검증 실패(CLOSED) 시 오케스트레이터 취소가 호출되지 않는다") {
                val cycle = saveCycle(status = TradingCycleStatus.CLOSED)

                shouldThrow<AlreadyClosedException> { tradingService.cancel(cycle.id) }

                verify(exactly = 0) { cycleOrchestrator.cancel(any()) }
            }
        }
    }
}
