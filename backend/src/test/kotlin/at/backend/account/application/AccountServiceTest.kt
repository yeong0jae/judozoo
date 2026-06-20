package at.backend.account.application

import at.backend.common.test.IntegrationTestBase
import at.backend.library.exception.EntityNotFoundException
import at.backend.platform.kiwoom.client.KiwoomTradingClient
import at.backend.platform.kiwoom.client.KiwoomTradingClient.AccountEvaluationResponse.StockHolding
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class AccountServiceTest(
    @Autowired private val accountService: AccountService,
    @Autowired private val tradingCycleRepository: TradingCycleJpaRepository,
    @Autowired private val kiwoomTradingClient: KiwoomTradingClient,
) : IntegrationTestBase() {

    private fun stubCashBalance(amount: String) {
        every { kiwoomTradingClient.fetchAvailableCash() } returns amount.toLong()
    }

    private fun stubHoldings(vararg holdings: StockHolding) {
        every { kiwoomTradingClient.fetchHoldingsRaw() } returns holdings.toList()
    }

    private fun holding(
        stkCd: String = "005930",
        stkNm: String = "삼성전자",
        rmndQty: String = "10",
        avgPrc: String = "70000",
        curPrc: String = "71000",
    ) = StockHolding(
        stk_cd = stkCd,
        stk_nm = stkNm,
        rmnd_qty = rmndQty,
        avg_prc = avgPrc,
        cur_prc = curPrc,
    )

    private fun saveCycle(
        stockCode: String = "005930",
        perBuyAmount: Long = 100_000L,
        status: TradingCycleStatus = TradingCycleStatus.INITIATED,
    ) = tradingCycleRepository.save(
        TradingCycle(
            accountNo = "00000000",
            stockCode = stockCode,
            stockName = "삼성전자",
            perBuyAmount = perBuyAmount,
            splitSellRatio = BigDecimal("0.5"),
            breakevenThresholdPct = BigDecimal("0.5"),
            stopLossPct = BigDecimal("-2.0"),
            status = status,
        )
    )

    init {
        beforeEach {
            clearMocks(kiwoomTradingClient)
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
            test("매수 집행 전이면 perBuyAmount 전액이 예약된다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.INITIATED)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 100_000L
                result.availableBalance shouldBe 900_000L
            }
        }

        context("BUYING 사이클") {
            test("매수 진행 중이면 perBuyAmount 전액이 예약된다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.BUYING)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 100_000L
                result.availableBalance shouldBe 900_000L
            }
        }

        context("HOLDING 사이클") {
            test("체결 완료된 보유 사이클은 예약금에 포함되지 않는다") {
                stubCashBalance("1000000")
                saveCycle(perBuyAmount = 100_000L, status = TradingCycleStatus.HOLDING)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
            }
        }

        context("비활성 사이클은 예약금에서 제외된다") {
            test("LIQUIDATING 사이클은 예약금에 포함되지 않는다") {
                stubCashBalance("1000000")
                saveCycle(status = TradingCycleStatus.LIQUIDATING)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
                result.availableBalance shouldBe 1_000_000L
            }

            test("CLOSED 사이클은 예약금에 포함되지 않는다") {
                stubCashBalance("1000000")
                saveCycle(status = TradingCycleStatus.CLOSED)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 0L
                result.availableBalance shouldBe 1_000_000L
            }
        }

        context("여러 활성 사이클") {
            test("활성 사이클의 예약 금액이 합산된다") {
                stubCashBalance("1000000")
                saveCycle(stockCode = "005930", perBuyAmount = 100_000L, status = TradingCycleStatus.INITIATED)
                saveCycle(stockCode = "035420", perBuyAmount = 200_000L, status = TradingCycleStatus.BUYING)

                val result = accountService.getBalance()

                result.reservedAmount shouldBe 300_000L
                result.availableBalance shouldBe 700_000L
            }
        }

        context("보유 주식 조회") {
            test("잔고가 비어있으면 빈 목록을 반환한다") {
                stubHoldings()

                accountService.getHoldings() shouldBe emptyList()
            }

            test("보유 수량이 0인 행은 결과에서 제외된다") {
                stubHoldings(
                    holding(stkCd = "005930", rmndQty = "10"),
                    holding(stkCd = "035420", rmndQty = "0"),
                )

                val result = accountService.getHoldings()

                result.map { it.stockCode } shouldBe listOf("005930")
            }

            test("평가손익과 평가손익률은 매도 비용(수수료·세금)을 반영한 손익분기가 기준으로 산출된다") {
                stubHoldings(
                    holding(stkCd = "005930", rmndQty = "10", avgPrc = "70000", curPrc = "71000"),
                )

                val result = accountService.getHoldings().single()

                result.qty shouldBe 10
                result.avgBuyPrice shouldBe 70_000L
                result.currentPrice shouldBe 71_000L
                // 손익분기가 = 70,000 * (1 + 0.0025) = 70,175 → 순손익 (71,000 - 70,175) * 10 = 8,250
                result.evalProfit shouldBe 8_250L
                result.evalProfitRate shouldBe ((71_000.0 - 70_175.0) / 70_000.0)
            }

            test("OPEN 상태(LIQUIDATING 포함) 사이클이 있는 종목은 hasActiveCycle=true") {
                stubHoldings(
                    holding(stkCd = "005930"),
                    holding(stkCd = "035420"),
                    holding(stkCd = "000660"),
                )
                saveCycle(stockCode = "005930", status = TradingCycleStatus.BUYING)
                saveCycle(stockCode = "035420", status = TradingCycleStatus.LIQUIDATING)

                val result = accountService.getHoldings().associate { it.stockCode to it.hasActiveCycle }

                result["005930"] shouldBe true
                result["035420"] shouldBe true
                result["000660"] shouldBe false
            }

            test("CLOSED 사이클만 있는 종목은 hasActiveCycle=false") {
                stubHoldings(holding(stkCd = "005930"))
                saveCycle(stockCode = "005930", status = TradingCycleStatus.CLOSED)

                accountService.getHoldings().single().hasActiveCycle shouldBe false
            }
        }

        context("보유 전량 시장가 매도") {
            fun stubSellOk(odno: String = "0000999999") {
                every { kiwoomTradingClient.placeSellOrder(any(), any()) } returns odno
            }

            test("보유 종목이 없으면 EntityNotFoundException을 던진다") {
                stubHoldings()

                shouldThrow<EntityNotFoundException> {
                    accountService.liquidate("005930")
                }
            }

            test("보유 수량 0인 종목도 EntityNotFoundException을 던진다") {
                stubHoldings(holding(stkCd = "005930", rmndQty = "0"))

                shouldThrow<EntityNotFoundException> {
                    accountService.liquidate("005930")
                }
            }

            test("보유 종목을 보유 수량만큼 시장가 매도로 발송하고 주문번호를 반환한다") {
                stubHoldings(holding(stkCd = "005930", rmndQty = "7"))
                stubSellOk(odno = "0000777777")

                val result = accountService.liquidate("005930")

                result.stockCode shouldBe "005930"
                result.qty shouldBe 7
                result.orderNo shouldBe "0000777777"
                // Kiwoom은 orgno(원장 번호) 개념 미사용 — 빈 문자열
                result.krxFwdgOrdOrgno shouldBe ""
                verify { kiwoomTradingClient.placeSellOrder("005930", 7) }
            }

            test("활성 사이클이 있는 종목이어도 API 레벨에선 매도 발송한다 (UI 가드가 1차 방어선)") {
                stubHoldings(holding(stkCd = "005930", rmndQty = "5"))
                stubSellOk()
                saveCycle(stockCode = "005930", status = TradingCycleStatus.LIQUIDATING)

                accountService.liquidate("005930").qty shouldBe 5

                verify { kiwoomTradingClient.placeSellOrder("005930", 5) }
            }
        }
    }
}
