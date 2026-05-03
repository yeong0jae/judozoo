package at.backend.account.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class)
class AccountServiceTest(
    @Autowired private val accountService: AccountService,
    @Autowired private val tradingCycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
) : IntegrationTestBase() {

    private fun stubCashBalance(amount: String) {
        every { kisRestClient.getBalance() } returns KisBalanceResponse(
            output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = amount))
        )
    }

    private fun saveCycle(
        stockCode: String = "005930",
        perBuyAmount: Long = 100_000L,
        status: TradingCycleStatus = TradingCycleStatus.INITIATED,
        buyAttempt: Int = 0,
    ) = tradingCycleRepository.save(
        TradingCycle(
            stockCode = stockCode,
            stockName = "삼성전자",
            perBuyAmount = perBuyAmount,
            buyIntervalMin = 3,
            splitSellRatio = BigDecimal("0.5"),
            midwayProfitPct = BigDecimal("1.5"),
            breakevenThresholdPct = BigDecimal("0.5"),
            stopLossPct = BigDecimal("-0.02"),
            status = status,
            buyAttempt = buyAttempt,
        )
    )

    init {
        beforeEach {
            clearMocks(kisRestClient)
            tradingCycleRepository.deleteAll()
        }

        context("활성 사이클이 없을 때") {
            test("예약금은 0이고 가용 잔고는 현금 잔고와 같다") {
                stubCashBalance("1000000")

                val result = accountService.getBalance()

                result.cashBalance shouldBe 1_000_000L
                result.reservedAmount shouldBe 0L
                result.availableBalance shouldBe 1_000_000L
            }
        }

        context("INITIATED 사이클") {
            test("매수 회차 0이면 perBuyAmount × 3 만큼 예약된다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.INITIATED, buyAttempt = 0)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 300_000L
                result.availableBalance shouldBe 700_000L
            }
        }

        context("BUYING 사이클") {
            test("1회차 완료 시 잔여 매수 회차(2)만큼만 예약된다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.BUYING, buyAttempt = 1)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 200_000L
                result.availableBalance shouldBe 800_000L
            }

            test("3회차까지 완료되면 예약금이 0이다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.BUYING, buyAttempt = 3)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
                result.availableBalance shouldBe 1_000_000L
            }
        }

        context("HOLDING 사이클") {
            test("3회차까지 완료된 보유 사이클은 예약금에 포함되지 않는다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.HOLDING, buyAttempt = 3)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
            }
        }

        context("비활성 사이클은 예약금에서 제외된다") {
            test("LIQUIDATING 사이클은 예약금에 포함되지 않는다") {
                stubCashBalance("1000000")
                saveCycle(status = TradingCycleStatus.LIQUIDATING, buyAttempt = 1)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
                result.availableBalance shouldBe 1_000_000L
            }

            test("CLOSED 사이클은 예약금에 포함되지 않는다") {
                stubCashBalance("1000000")
                saveCycle(status = TradingCycleStatus.CLOSED, buyAttempt = 1)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
                result.availableBalance shouldBe 1_000_000L
            }
        }

        context("여러 활성 사이클") {
            test("활성 사이클의 잔여 매수 금액이 합산되어 예약된다") {
                stubCashBalance("1000000")
                saveCycle(stockCode = "005930", perBuyAmount = 100_000L, status = TradingCycleStatus.INITIATED, buyAttempt = 0)
                saveCycle(stockCode = "035420", perBuyAmount = 200_000L, status = TradingCycleStatus.BUYING, buyAttempt = 1)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 700_000L
                result.availableBalance shouldBe 300_000L
            }
        }
    }
}
