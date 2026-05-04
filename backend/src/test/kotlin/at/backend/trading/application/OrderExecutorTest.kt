package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClientException
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
@TestPropertySource(properties = ["trading.order.sell-retry-delay-millis=20"])
class OrderExecutorTest(
    @Autowired private val orderExecutor: OrderExecutor,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
) : IntegrationTestBase() {

    private fun saveCycle(): TradingCycle = cycleRepository.save(
        TradingCycle(
            stockCode = "005930",
            stockName = "삼성전자",
            perBuyAmount = 1_000_000L,
            buyIntervalMin = 3,
            splitSellRatio = BigDecimal("0.5"),
            midwayProfitPct = BigDecimal("3.0"),
            breakevenThresholdPct = BigDecimal("2.0"),
            stopLossPct = BigDecimal("-0.02"),
            status = TradingCycleStatus.HOLDING,
            buyAttempt = 3,
        )
    )

    private fun stubCurrentPrice(price: Int) {
        every { kisRestClient.getCurrentPrice(any()) } returns
            KisCurrentPriceResponse(KisCurrentPriceResponse.Output(stckPrpr = price.toString()))
    }

    private fun stubSubmitOrder(odno: String = "0000123456", orgno: String = "00950") {
        every { kisRestClient.submitOrder(any(), any(), any()) } returns
            KisOrderResponse(
                rtCd = "0",
                msgCd = "APBK0013",
                msg1 = "주문 전송 완료",
                output = KisOrderResponse.Output(krxFwdgOrdOrgno = orgno, odno = odno, ordTmd = "104518"),
            )
    }

    init {
        beforeEach {
            clearMocks(kisRestClient, answers = false)
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
        }

        context("매수 회차 발송") {
            test("정상 발송 시 Order에 KIS 식별자가 갱신된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrder(odno = "0000111111")
                val cycle = saveCycle()

                val outcome = orderExecutor.executeBuyTry(cycle, attempt = 1)

                outcome.shouldBeInstanceOf<OrderExecutor.BuyOutcome.Submitted>()
                outcome.order.kisOrderNo shouldBe "0000111111"
                outcome.order.krxFwdgOrdOrgno shouldBe "00950"
                outcome.order.orderQty shouldBe 14
                outcome.order.side shouldBe "BUY"

                verify { kisRestClient.submitOrder("005930", "BUY", 14) }
            }

            test("perBuyAmount가 1주 가격보다 작으면 발송 없이 스킵된다") {
                stubCurrentPrice(2_000_000)
                val cycle = saveCycle()

                val outcome = orderExecutor.executeBuyTry(cycle, attempt = 1)

                outcome.shouldBeInstanceOf<OrderExecutor.BuyOutcome.Skipped>()
                verify(exactly = 0) { kisRestClient.submitOrder(any(), any(), any()) }
                orderRepository.findByCycleId(cycle.id).size shouldBe 0
            }

            test("KIS 발송 실패 시 Order는 FAILED로 기록되고 회차 스킵") {
                stubCurrentPrice(70_000)
                every { kisRestClient.submitOrder(any(), any(), any()) } throws RestClientException("4xx")
                val cycle = saveCycle()

                val outcome = orderExecutor.executeBuyTry(cycle, attempt = 1)

                outcome.shouldBeInstanceOf<OrderExecutor.BuyOutcome.Skipped>()
                val saved = orderRepository.findByCycleId(cycle.id).single()
                saved.status shouldBe "FAILED"
                saved.lastError shouldBe "4xx"
            }
        }

        context("매도 시그널 발송") {
            test("StopLoss 정상 발송 시 SELL Order가 저장된다") {
                stubSubmitOrder(odno = "0000222222")
                val cycle = saveCycle()

                val outcome = orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                outcome.shouldBeInstanceOf<OrderExecutor.SellOutcome.Submitted>()
                outcome.order.kisOrderNo shouldBe "0000222222"
                outcome.order.side shouldBe "SELL"
                outcome.order.orderQty shouldBe 10
            }

            test("in-flight 매도 미체결분이 있으면 effectiveQty가 차감된다") {
                stubSubmitOrder()
                val cycle = saveCycle()
                orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = "SELL",
                        trigger = "TpStage",
                        orderQty = 6,
                        filledQty = 2,
                        status = "PENDING",
                    )
                )

                orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                verify { kisRestClient.submitOrder("005930", "SELL", 6) }
            }

            test("in-flight 미체결분이 intentQty 이상이면 NoQty로 즉시 종료") {
                val cycle = saveCycle()
                orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = "SELL",
                        trigger = "TpStage",
                        orderQty = 10,
                        filledQty = 0,
                        status = "PENDING",
                    )
                )

                val outcome = orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                outcome shouldBe OrderExecutor.SellOutcome.NoQty
                verify(exactly = 0) { kisRestClient.submitOrder(any(), any(), any()) }
            }

            test("Breakeven 시그널은 현재가가 매수가 초과면 isAlive=false로 즉시 종료") {
                val cycle = saveCycle()

                val outcome = orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.Breakeven,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 71_000,
                    currentBar = null,
                )

                outcome shouldBe OrderExecutor.SellOutcome.SignalDead
                verify(exactly = 0) { kisRestClient.submitOrder(any(), any(), any()) }
            }

            test("첫 시도 실패 후 재시도 성공") {
                var calls = 0
                every { kisRestClient.submitOrder(any(), any(), any()) } answers {
                    calls += 1
                    if (calls == 1) throw RestClientException("5xx") else KisOrderResponse(
                        rtCd = "0", msgCd = "APBK0013", msg1 = "OK",
                        output = KisOrderResponse.Output(krxFwdgOrdOrgno = "00950", odno = "0000333333", ordTmd = "104518"),
                    )
                }
                val cycle = saveCycle()

                val outcome = orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                outcome.shouldBeInstanceOf<OrderExecutor.SellOutcome.Submitted>()
                outcome.order.kisOrderNo shouldBe "0000333333"
                calls shouldBe 2
            }
        }
    }
}
