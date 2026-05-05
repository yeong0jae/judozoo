package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisDailyCcldResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
@TestPropertySource(properties = [
    "trading.order.sell-retry-delay-millis=20",
    "trading.order.reconcile-delay-millis=600000",
])
class OrderExecutorTest(
    @Autowired private val orderExecutor: OrderExecutor,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val executionRepository: ExecutionJpaRepository,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
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
            stopLossPct = BigDecimal("-2.0"),
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
            executionRepository.deleteAll()
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
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

            test("시그널이 죽으면 in-flight SELL 주문 잔량을 cancelRemainder로 취소하고 CANCELLED로 마킹한다") {
                every { kisRestClient.cancelRemainder(any(), any()) } returns
                    KisOrderResponse(
                        rtCd = "0", msgCd = "OK", msg1 = "취소 완료",
                        output = KisOrderResponse.Output(krxFwdgOrdOrgno = "00950", odno = "CXL0001", ordTmd = "104518"),
                    )
                val cycle = saveCycle()
                val pending = orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = "SELL",
                        trigger = "TpStage",
                        orderQty = 6,
                        filledQty = 2,
                        kisOrderNo = "ODNO_OLD",
                        krxFwdgOrdOrgno = "00950",
                        status = "PENDING",
                    )
                )

                val outcome = orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.Breakeven,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 71_000,
                    currentBar = null,
                )

                outcome shouldBe OrderExecutor.SellOutcome.SignalDead
                verify { kisRestClient.cancelRemainder("00950", "ODNO_OLD") }
                orderRepository.findById(pending.id).get().status shouldBe "CANCELLED"
            }

            test("kisOrderNo가 없는 in-flight 주문은 cancelRemainder 호출 없이 CANCELLED로만 마킹한다") {
                val cycle = saveCycle()
                val unsentPending = orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = "SELL",
                        trigger = "TpStage",
                        orderQty = 5,
                        filledQty = 0,
                        kisOrderNo = null,
                        krxFwdgOrdOrgno = null,
                        status = "PENDING",
                    )
                )

                orderExecutor.executeSell(
                    cycle = cycle,
                    signal = Signal.Breakeven,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 71_000,
                    currentBar = null,
                )

                verify(exactly = 0) { kisRestClient.cancelRemainder(any(), any()) }
                orderRepository.findById(unsentPending.id).get().status shouldBe "CANCELLED"
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

        context("WS 통보 누락 시 reconcile") {
            fun savePendingBuy(
                kisOrderNo: String? = "ODNO_BUY",
                orderQty: Int = 10,
            ): Order = orderRepository.save(
                Order(
                    cycleId = saveCycle().id,
                    side = "BUY",
                    trigger = "BUY_1",
                    orderQty = orderQty,
                    kisOrderNo = kisOrderNo,
                    krxFwdgOrdOrgno = if (kisOrderNo == null) null else "00950",
                    status = "PENDING",
                )
            )

            fun ccldRow(
                odno: String,
                pdno: String = "005930",
                qty: Int = 10,
                price: Int = 70_000,
                side: String = "BUY",
                ts: LocalDateTime = LocalDateTime.now(),
            ) = KisDailyCcldResponse.Output(
                pdno = pdno,
                odno = odno,
                ordDt = ts.format(DateTimeFormatter.BASIC_ISO_DATE),
                ordTmd = ts.format(DateTimeFormatter.ofPattern("HHmmss")),
                totCcldQty = qty.toString(),
                avgPrvs = price.toString(),
                sllBuyDvsnCd = if (side == "BUY") "02" else "01",
            )

            test("kisOrderNo 매칭 1건 → Order/Execution 갱신") {
                val order = savePendingBuy()
                every { kisRestClient.getDailyExecutions(any(), any()) } returns
                        KisDailyCcldResponse(output1 = listOf(ccldRow(odno = "ODNO_BUY", qty = 10, price = 71_000)))

                val outcome = orderExecutor.reconcile(order.id, "005930")

                outcome.shouldBeInstanceOf<OrderExecutor.ReconcileOutcome.Matched>()
                val refreshed = orderRepository.findById(order.id).get()
                refreshed.filledQty shouldBe 10
                refreshed.status shouldBe "FILLED"
                executionRepository.findByOrderId(order.id).single().executedPrice shouldBe 71_000
            }

            test("0건 매칭 → no-op (재발송 안전, status 유지)") {
                val order = savePendingBuy(kisOrderNo = "ODNO_BUY")
                every { kisRestClient.getDailyExecutions(any(), any()) } returns
                        KisDailyCcldResponse(output1 = emptyList())

                val outcome = orderExecutor.reconcile(order.id, "005930")

                outcome shouldBe OrderExecutor.ReconcileOutcome.NoMatch
                val refreshed = orderRepository.findById(order.id).get()
                refreshed.status shouldBe "PENDING"
                refreshed.filledQty shouldBe 0
                executionRepository.findByOrderId(order.id) shouldBe emptyList()
            }

            test("kisOrderNo가 없으면 시간/종목/side/수량 fallback으로 매칭한다") {
                val order = savePendingBuy(kisOrderNo = null, orderQty = 7)
                every { kisRestClient.getDailyExecutions(any(), any()) } returns
                        KisDailyCcldResponse(
                            output1 = listOf(
                                ccldRow(odno = "OTHER", side = "SELL", qty = 7),
                                ccldRow(odno = "MATCHED", qty = 7, price = 70_500),
                                ccldRow(odno = "DIFF_QTY", qty = 99, price = 70_500),
                            )
                        )

                val outcome = orderExecutor.reconcile(order.id, "005930")

                outcome.shouldBeInstanceOf<OrderExecutor.ReconcileOutcome.Matched>()
                orderRepository.findById(order.id).get().filledQty shouldBe 7
            }

            test("fallback에서 다중 매칭이면 manual review로 마킹한다") {
                val order = savePendingBuy(kisOrderNo = null, orderQty = 5)
                every { kisRestClient.getDailyExecutions(any(), any()) } returns
                        KisDailyCcldResponse(
                            output1 = listOf(
                                ccldRow(odno = "DUP1", qty = 5, price = 70_000),
                                ccldRow(odno = "DUP2", qty = 5, price = 70_500),
                            )
                        )

                val outcome = orderExecutor.reconcile(order.id, "005930")

                outcome shouldBe OrderExecutor.ReconcileOutcome.MultipleMatches
                orderRepository.findById(order.id).get().status shouldBe "NEEDS_REVIEW"
            }

            test("이미 부분 체결된 주문은 skipped (WS가 처리 중)") {
                val partial = orderRepository.save(
                    Order(
                        cycleId = saveCycle().id,
                        side = "BUY",
                        trigger = "BUY_1",
                        orderQty = 10,
                        filledQty = 4,
                        kisOrderNo = "ODNO_BUY",
                        krxFwdgOrdOrgno = "00950",
                        status = "PENDING",
                    )
                )

                val outcome = orderExecutor.reconcile(partial.id, "005930")

                outcome shouldBe OrderExecutor.ReconcileOutcome.Skipped
                verify(exactly = 0) { kisRestClient.getDailyExecutions(any(), any()) }
            }
        }
    }
}
