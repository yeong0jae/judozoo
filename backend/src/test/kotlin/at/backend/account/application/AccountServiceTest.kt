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

    private fun stubHoldings(vararg holdings: KisBalanceResponse.Holding) {
        every { kisRestClient.getBalance() } returns KisBalanceResponse(
            output1 = holdings.toList(),
            output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = "0")),
        )
    }

    private fun holding(
        pdno: String = "005930",
        prdtName: String = "삼성전자",
        hldgQty: String = "10",
        pchsAvgPric: String = "70000",
        prpr: String = "71000",
    ) = KisBalanceResponse.Holding(
        pdno = pdno,
        prdtName = prdtName,
        hldgQty = hldgQty,
        pchsAvgPric = pchsAvgPric,
        prpr = prpr,
        evluPflsAmt = "0",
        evluPflsRt = "0",
    )

    private fun saveCycle(
        stockCode: String = "005930",
        perBuyAmount: Long = 100_000L,
        status: TradingCycleStatus = TradingCycleStatus.INITIATED,
        buyAttempt: Int = 0,
    ) = tradingCycleRepository.save(
        TradingCycle(
            accountNo = "00000000",
            stockCode = stockCode,
            stockName = "삼성전자",
            perBuyAmount = perBuyAmount,
            buyIntervalMin = 3,
            splitSellRatio = BigDecimal("0.5"),
            midwayProfitPct = BigDecimal("1.5"),
            breakevenThresholdPct = BigDecimal("0.5"),
            stopLossPct = BigDecimal("-2.0"),
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

        context("보유 주식 조회") {
            test("KIS 잔고가 비어있으면 빈 목록을 반환한다") {
                stubHoldings()

                accountService.getHoldings() shouldBe emptyList()
            }

            test("보유 수량이 0인 행은 결과에서 제외된다") {
                stubHoldings(
                    holding(pdno = "005930", hldgQty = "10"),
                    holding(pdno = "035420", hldgQty = "0"),
                )

                val result = accountService.getHoldings()

                result.map { it.stockCode } shouldBe listOf("005930")
            }

            test("평가손익과 평가손익률은 현재가와 매입평균가에서 산출된다") {
                stubHoldings(
                    holding(pdno = "005930", hldgQty = "10", pchsAvgPric = "70000", prpr = "71000"),
                )

                val result = accountService.getHoldings().single()

                result.qty shouldBe 10
                result.avgBuyPrice shouldBe 70_000L
                result.currentPrice shouldBe 71_000L
                result.evalProfit shouldBe 10_000L
                result.evalProfitRate shouldBe (1_000.0 / 70_000.0)
            }

            test("OPEN 상태(LIQUIDATING 포함) 사이클이 있는 종목은 hasActiveCycle=true") {
                stubHoldings(
                    holding(pdno = "005930"),
                    holding(pdno = "035420"),
                    holding(pdno = "000660"),
                )
                saveCycle(stockCode = "005930", status = TradingCycleStatus.BUYING)
                saveCycle(stockCode = "035420", status = TradingCycleStatus.LIQUIDATING)

                val result = accountService.getHoldings().associate { it.stockCode to it.hasActiveCycle }

                result["005930"] shouldBe true
                result["035420"] shouldBe true
                result["000660"] shouldBe false
            }

            test("CLOSED 사이클만 있는 종목은 hasActiveCycle=false") {
                stubHoldings(holding(pdno = "005930"))
                saveCycle(stockCode = "005930", status = TradingCycleStatus.CLOSED)

                accountService.getHoldings().single().hasActiveCycle shouldBe false
            }
        }
    }
}
