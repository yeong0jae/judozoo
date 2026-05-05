package at.backend.trading.application

import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.TradingValidationException
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDateTime

class TradingValidatorTest : FunSpec({

    val kisRestClient = mockk<KisRestClient>()
    val tradingCycleRepository = mockk<TradingCycleJpaRepository>()
    val commandGate = CommandGate()

    fun nowAt(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 1, 2, hour, minute)

    val validator = TradingValidator(kisRestClient, tradingCycleRepository, commandGate)

    val validInput = TradingInput(
        stockCode = "005930",
        perBuyAmount = 100_000L,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.5"),
        midwayProfitPct = BigDecimal("1.5"),
        breakevenThresholdPct = BigDecimal("0.5"),
        stopLossPct = BigDecimal("2.0"),
    )

    fun stubAllPass() {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
            output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"))
        )
        every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
            output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
        )
        every { kisRestClient.getBalance() } returns KisBalanceResponse(
            output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = "1000000"))
        )
        every { tradingCycleRepository.findByStatusIn(any()) } returns emptyList()
        every { tradingCycleRepository.findByStockCodeAndStatusIn(any(), any()) } returns emptyList()
        every { kisRestClient.checkHoliday(any()) } returns KisHolidayResponse(
            output = listOf(KisHolidayResponse.Output(bzdyYn = "Y"))
        )
    }

    context("정상 검증 통과") {
        test("모든 조건 충족 시 종목명 반환") {
            stubAllPass()
            val stockName = validator.validate(validInput, nowAt(10, 0))
            stockName shouldBe "삼성전자"
        }
    }

    context("입력값 범위 오류") {
        test("perBuyAmount가 0이면 INVALID_PARAMETER") {
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput.copy(perBuyAmount = 0), nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INVALID_PARAMETER
        }

        test("buyIntervalMin이 0이면 INVALID_PARAMETER") {
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput.copy(buyIntervalMin = 0), nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INVALID_PARAMETER
        }

        test("splitSellRatio가 0이면 INVALID_PARAMETER") {
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput.copy(splitSellRatio = BigDecimal.ZERO), nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INVALID_PARAMETER
        }

        test("splitSellRatio가 1 이상이면 INVALID_PARAMETER") {
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput.copy(splitSellRatio = BigDecimal.ONE), nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INVALID_PARAMETER
        }

        test("stopLossPct가 0이면 INVALID_PARAMETER") {
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput.copy(stopLossPct = BigDecimal.ZERO), nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INVALID_PARAMETER
        }
    }

    context("종목 미존재") {
        test("KIS 검색 결과가 없으면 STOCK_NOT_FOUND") {
            every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(output = emptyList())

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.STOCK_NOT_FOUND
        }
    }

    context("1주 가격 초과") {
        test("현재가가 perBuyAmount를 초과하면 PRICE_BELOW_ONE_SHARE") {
            every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
                output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"))
            )
            every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
                output = KisCurrentPriceResponse.Output(stckPrpr = "200000")
            )

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.PRICE_BELOW_ONE_SHARE
        }
    }

    context("잔고 부족") {
        test("접수 대기(INITIATED) 사이클 예약금 차감 후 잔고가 부족하면 INSUFFICIENT_BALANCE") {
            every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
                output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"))
            )
            every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
                output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
            )
            every { kisRestClient.getBalance() } returns KisBalanceResponse(
                output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = "1000000"))
            )
            val initiatedCycle = mockk<TradingCycle>()
            every { initiatedCycle.perBuyAmount } returns 950_000L
            every { tradingCycleRepository.findByStatusIn(listOf(TradingCycleStatus.INITIATED)) } returns listOf(initiatedCycle)

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INSUFFICIENT_BALANCE
        }
    }

    context("동일 종목 활성 사이클 중복") {
        test("같은 종목의 활성 사이클이 존재하면 DUPLICATE_COMMAND") {
            every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
                output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"))
            )
            every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
                output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
            )
            every { kisRestClient.getBalance() } returns KisBalanceResponse(
                output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = "1000000"))
            )
            every { tradingCycleRepository.findByStatusIn(any()) } returns emptyList()
            val existingCycle = mockk<TradingCycle>()
            every { tradingCycleRepository.findByStockCodeAndStatusIn(any(), any()) } returns listOf(existingCycle)

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.DUPLICATE_COMMAND
        }
    }

    context("컷오프 초과") {
        test("현재 시각이 컷오프(15:14) 이후면 CUTOFF_PASSED — buyIntervalMin=3") {
            stubAllPass()
            // cutoff = 15:20 - 3*2 = 15:14
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(15, 15))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.CUTOFF_PASSED
        }

        test("컷오프 직전이면 통과") {
            stubAllPass()
            val stockName = validator.validate(validInput, nowAt(15, 13))
            stockName shouldBe "삼성전자"
        }
    }

    context("휴장일") {
        test("KIS 영업일 조회에서 bzdy_yn=N이면 HOLIDAY") {
            every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
                output = listOf(KisStockSearchResponse.Output(pdno = "005930", prdtAbrvName = "삼성전자"))
            )
            every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
                output = KisCurrentPriceResponse.Output(stckPrpr = "70000")
            )
            every { kisRestClient.getBalance() } returns KisBalanceResponse(
                output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = "1000000"))
            )
            every { tradingCycleRepository.findByStatusIn(any()) } returns emptyList()
            every { tradingCycleRepository.findByStockCodeAndStatusIn(any(), any()) } returns emptyList()
            every { kisRestClient.checkHoliday(any()) } returns KisHolidayResponse(
                output = listOf(KisHolidayResponse.Output(bzdyYn = "N"))
            )

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.HOLIDAY
        }
    }

    context("거래 시간 외") {
        // 장 마감 후(>15:30)는 컷오프(check 6)가 먼저 실행되어 CUTOFF_PASSED로 처리됨
        // OUT_OF_TRADING_HOURS는 장 시작 전(pre-market) 케이스만 실질적으로 발생
        test("장 시작 전(08:59)이면 OUT_OF_TRADING_HOURS") {
            stubAllPass()

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(8, 59))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.OUT_OF_TRADING_HOURS
        }
    }

    context("명령 접수 게이트") {
        test("게이트가 닫혀있으면 다른 검증보다 먼저 COMMAND_GATE_CLOSED") {
            commandGate.close()

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.COMMAND_GATE_CLOSED

            commandGate.open()
        }
    }
})
