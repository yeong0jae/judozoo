package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisOrderRejectedException
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.order.OrderStatus
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClientException
import java.math.BigDecimal

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
@TestPropertySource(
    properties = [
        "trading.order.sell-retry-delay-millis=20",
        "trading.order.egw-retry-base-delay-millis=20",
    ]
)
class OrderServiceTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val executionRepository: ExecutionJpaRepository,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private fun saveCycle(): TradingCycle = cycleRepository.save(
        TradingCycle(
            accountNo = "00000000",
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
        every { kisRestClient.requestOrder(any(), any(), any()) } returns
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

                orderService.placeOrder(cycle, attempt = 1)

                val saved = orderRepository.findByCycleId(cycle.id).single()
                saved.orderNo shouldBe "0000111111"
                saved.fwdgOrdOrgno shouldBe "00950"
                saved.orderQty shouldBe 14
                saved.side shouldBe OrderSide.BUY
                saved.status shouldBe OrderStatus.PENDING

                verify { kisRestClient.requestOrder("005930", "BUY", 14) }
            }

            test("perBuyAmount가 1주 가격보다 작으면 발송 없이 스킵된다") {
                stubCurrentPrice(2_000_000)
                val cycle = saveCycle()

                orderService.placeOrder(cycle, attempt = 1)

                verify(exactly = 0) { kisRestClient.requestOrder(any(), any(), any()) }
                orderRepository.findByCycleId(cycle.id).size shouldBe 0
            }

            test("KIS 명시 거부(rt_cd≠0)는 FAILED로 확정 + 회차 스킵") {
                stubCurrentPrice(70_000)
                every { kisRestClient.requestOrder(any(), any(), any()) } throws
                        KisOrderRejectedException(msgCd = "EGW00201", msg = "초당 거래건수 초과")
                val cycle = saveCycle()

                orderService.placeOrder(cycle, attempt = 1)

                val saved = orderRepository.findByCycleId(cycle.id).single()
                saved.status shouldBe OrderStatus.FAILED
                saved.lastError!! shouldBe "[EGW00201] 초당 거래건수 초과"
            }

            test("응답 파싱 실패 / HTTP 오류도 FAILED로 확정 (WS 통보 100% 가정)") {
                stubCurrentPrice(70_000)
                every { kisRestClient.requestOrder(any(), any(), any()) } throws RestClientException("응답 파싱 실패")
                val cycle = saveCycle()

                orderService.placeOrder(cycle, attempt = 1)

                val saved = orderRepository.findByCycleId(cycle.id).single()
                saved.status shouldBe OrderStatus.FAILED
                saved.lastError shouldBe "응답 파싱 실패"
                saved.orderNo shouldBe null
            }

            test("현재가 조회 실패는 회차 스킵으로 처리 — 사이클 전체가 죽지 않는다") {
                every { kisRestClient.getCurrentPrice(any()) } throws RestClientException("EGW00201")
                val cycle = saveCycle()

                // 예외 전파 없이 정상 리턴해야 함
                orderService.placeOrder(cycle, attempt = 1)

                verify(exactly = 0) { kisRestClient.requestOrder(any(), any(), any()) }
                orderRepository.findByCycleId(cycle.id).size shouldBe 0
            }

            test("매수 발송 EGW00201은 회차 내에서 재시도하고, 성공 시 Order에 odno가 저장된다") {
                stubCurrentPrice(70_000)
                var calls = 0
                every { kisRestClient.requestOrder(any(), any(), any()) } answers {
                    calls += 1
                    if (calls == 1) throw KisOrderRejectedException(msgCd = "EGW00201", msg = "초당 거래건수 초과")
                    KisOrderResponse(
                        rtCd = "0", msgCd = "APBK0013", msg1 = "OK",
                        output = KisOrderResponse.Output(
                            krxFwdgOrdOrgno = "00950",
                            odno = "0000888888",
                            ordTmd = "104518",
                        ),
                    )
                }
                val cycle = saveCycle()

                orderService.placeOrder(cycle, attempt = 1)

                calls shouldBe 2
                val saved = orderRepository.findByCycleId(cycle.id).single()
                saved.status shouldBe OrderStatus.PENDING
                saved.orderNo shouldBe "0000888888"
            }

        }

        context("매도 시그널 발송") {
            test("StopLoss 정상 발송 시 SELL Order가 저장된다") {
                stubSubmitOrder(odno = "0000222222")
                val cycle = saveCycle()

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                val saved = orderRepository.findByCycleId(cycle.id).single { it.side == OrderSide.SELL }
                saved.orderNo shouldBe "0000222222"
                saved.orderQty shouldBe 10
            }

            test("in-flight 매도 미체결분이 있으면 effectiveQty가 차감된다") {
                stubSubmitOrder()
                val cycle = saveCycle()
                orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = OrderSide.SELL,
                        trigger = "TpStage",
                        orderQty = 6,
                        filledQty = 2,
                        status = OrderStatus.PENDING,
                    )
                )

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                verify { kisRestClient.requestOrder("005930", "SELL", 6) }
            }

            test("in-flight 미체결분이 intentQty 이상이면 발송 없이 즉시 종료") {
                val cycle = saveCycle()
                orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = OrderSide.SELL,
                        trigger = "TpStage",
                        orderQty = 10,
                        filledQty = 0,
                        status = OrderStatus.PENDING,
                    )
                )

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                verify(exactly = 0) { kisRestClient.requestOrder(any(), any(), any()) }
            }

            test("Breakeven 시그널은 현재가가 매수가 초과면 isAlive=false로 즉시 종료") {
                val cycle = saveCycle()

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.Breakeven,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 71_000,
                    currentBar = null,
                )

                verify(exactly = 0) { kisRestClient.requestOrder(any(), any(), any()) }
            }

            test("시그널이 죽으면 in-flight SELL 주문 잔량을 cancelRemainder로 취소하고 CANCELLED로 마킹한다") {
                every { kisRestClient.cancelRemainder(any(), any()) } returns
                        KisOrderResponse(
                            rtCd = "0", msgCd = "OK", msg1 = "취소 완료",
                            output = KisOrderResponse.Output(
                                krxFwdgOrdOrgno = "00950",
                                odno = "CXL0001",
                                ordTmd = "104518",
                            ),
                        )
                val cycle = saveCycle()
                val pending = orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = OrderSide.SELL,
                        trigger = "TpStage",
                        orderQty = 6,
                        filledQty = 2,
                        orderNo = "ODNO_OLD",
                        fwdgOrdOrgno = "00950",
                        status = OrderStatus.PENDING,
                    )
                )

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.Breakeven,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 71_000,
                    currentBar = null,
                )

                verify { kisRestClient.cancelRemainder("00950", "ODNO_OLD") }
                orderRepository.findById(pending.id).get().status shouldBe OrderStatus.CANCELLED
            }

            test("kisOrderNo가 없는 in-flight 주문은 cancelRemainder 호출 없이 CANCELLED로만 마킹한다") {
                val cycle = saveCycle()
                val unsentPending = orderRepository.save(
                    Order(
                        cycleId = cycle.id,
                        side = OrderSide.SELL,
                        trigger = "TpStage",
                        orderQty = 5,
                        filledQty = 0,
                        orderNo = null,
                        fwdgOrdOrgno = null,
                        status = OrderStatus.PENDING,
                    )
                )

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.Breakeven,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 71_000,
                    currentBar = null,
                )

                verify(exactly = 0) { kisRestClient.cancelRemainder(any(), any()) }
                orderRepository.findById(unsentPending.id).get().status shouldBe OrderStatus.CANCELLED
            }

            test("첫 시도 실패 후 재시도 성공") {
                var calls = 0
                every { kisRestClient.requestOrder(any(), any(), any()) } answers {
                    calls += 1
                    if (calls == 1) throw RestClientException("5xx") else KisOrderResponse(
                        rtCd = "0", msgCd = "APBK0013", msg1 = "OK",
                        output = KisOrderResponse.Output(
                            krxFwdgOrdOrgno = "00950",
                            odno = "0000333333",
                            ordTmd = "104518",
                        ),
                    )
                }
                val cycle = saveCycle()

                orderService.placeSell(
                    cycle = cycle,
                    signal = Signal.StopLoss,
                    intentQty = 10,
                    buyPrice = 70_000,
                    currentPrice = 68_000,
                    currentBar = null,
                )

                val submitted = orderRepository.findByCycleId(cycle.id).single { it.orderNo != null }
                submitted.orderNo shouldBe "0000333333"
                calls shouldBe 2
            }
        }

    }
}
