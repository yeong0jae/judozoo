package at.backend.trading.application

import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.broker.StockInfo
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.TradingValidationException
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDateTime

class TradingValidatorTest : FunSpec({

    val broker = mockk<BrokerTradingClient>().apply {
        every { accountNo } returns "00000000"
    }
    val tradingCycleRepository = mockk<TradingCycleJpaRepository>()

    fun nowAt(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 1, 2, hour, minute)

    val validator = TradingValidator(broker, tradingCycleRepository)

    val validInput = TradingInput(
        stockCode = "005930",
        perBuyQty = 1,
        buyIntervalMin = 3,
        splitSellRatio = BigDecimal("0.5"),
        midwayProfitPct = BigDecimal("1.5"),
        breakevenThresholdPct = BigDecimal("0.5"),
        stopLossPct = BigDecimal("2.0"),
    )

    fun stubAllPass() {
        every { broker.searchStock(any()) } returns StockInfo(code = "005930", name = "삼성전자")
        every { broker.currentPrice(any()) } returns 70_000L
        every { broker.availableCash() } returns 1_000_000L
        every { broker.isMarketOpen(any()) } returns true
        every { tradingCycleRepository.findByAccountNoAndStatusIn(any(), any()) } returns emptyList()
        every { tradingCycleRepository.findByAccountNoAndStockCodeAndStatusIn(any(), any(), any()) } returns emptyList()
    }

    context("정상 검증 통과") {
        test("모든 조건 충족 시 종목명 반환") {
            stubAllPass()
            val (stockName, _) = validator.validate(validInput, nowAt(10, 0))
            stockName shouldBe "삼성전자"
        }
    }

    context("입력값 범위 오류") {
        test("perBuyQty가 0이면 INVALID_PARAMETER") {
            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput.copy(perBuyQty = 0), nowAt(10, 0))
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
        test("브로커 검색 결과가 null이면 STOCK_NOT_FOUND") {
            every { broker.searchStock(any()) } returns null

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.STOCK_NOT_FOUND
        }
    }

    context("잔고 부족") {
        test("perBuyQty × currentPrice × MAX_BUY_ATTEMPT(3)이 잔고를 초과하면 INSUFFICIENT_BALANCE") {
            stubAllPass()
            // 1주 × 70,000원 × 3회 = 210,000원 > 잔고 200,000원
            every { broker.availableCash() } returns 200_000L

            val ex = shouldThrow<TradingValidationException> {
                validator.validate(validInput, nowAt(10, 0))
            }
            ex.errorCode shouldBe TradingValidationException.ErrorCode.INSUFFICIENT_BALANCE
        }
    }

    context("동일 종목 활성 사이클 중복") {
        test("같은 종목의 활성 사이클이 존재하면 DUPLICATE_COMMAND") {
            stubAllPass()
            val existingCycle = mockk<TradingCycle>()
            every { tradingCycleRepository.findByAccountNoAndStockCodeAndStatusIn(any(), any(), any()) } returns listOf(existingCycle)

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
            val (stockName, _) = validator.validate(validInput, nowAt(15, 13))
            stockName shouldBe "삼성전자"
        }
    }

    context("휴장일") {
        test("브로커가 휴장이라고 응답하면 HOLIDAY") {
            stubAllPass()
            every { broker.isMarketOpen(any()) } returns false

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

})
