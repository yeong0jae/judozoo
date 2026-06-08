package at.backend.trading.application.runner

import at.backend.common.test.*
import at.backend.market.application.BarPoller
import at.backend.market.application.PriceTickDataStream
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.trading.application.OrderService
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.order.OrderStatus
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

@Import(FixedTimeProviderConfig::class)
class TradingCycleRunnerTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val executionRepository: ExecutionJpaRepository,
    @Autowired private val priceTickDataStream: PriceTickDataStream,
    @Autowired private val barPoller: BarPoller,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val webSocketClient: at.backend.platform.kis.client.KisWebSocketClient,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun saveCycle(perBuyAmount: Long = 1_000_000): TradingCycle =
        cycleRepository.save(
            TradingCycle(
                accountNo = "00000000",
                stockCode = "005930",
                stockName = "삼성전자",
                perBuyAmount = perBuyAmount,
                splitSellRatio = BigDecimal("0.5"),
                breakevenThresholdPct = BigDecimal("2.0"),
                stopLossPct = BigDecimal("-2.0"),
                status = TradingCycleStatus.INITIATED,
            )
        )

    private fun runner(cycle: TradingCycle) = TradingCycleRunner(
        cycle = cycle,
        applicationScope = applicationScope,
        orderService = orderService,
        cycleRepository = cycleRepository,
        orderRepository = orderRepository,
        executionRepository = executionRepository,
        priceTickDataStream = priceTickDataStream,
        barPoller = barPoller,
        timeProvider = timeProvider,
        eventPublisher = org.springframework.context.ApplicationEventPublisher { },
        sellCostRate = 0.0025,
        buyFillWaitMillis = 500,
        holdingPollIntervalMillis = 10,
    )

    private fun stubCurrentPrice(price: Int) {
        every { kisRestClient.getCurrentPrice(any()) } returns
                KisCurrentPriceResponse(KisCurrentPriceResponse.Output(stckPrpr = price.toString()))
    }

    private fun stubSubmitOrderOk() {
        var counter = 0
        every { kisRestClient.requestOrder(any(), any(), any()) } answers {
            counter += 1
            KisOrderResponse(
                rtCd = "0", msgCd = "APBK0013", msg1 = "OK",
                output = KisOrderResponse.Output(
                    krxFwdgOrdOrgno = "00950",
                    odno = "ODNO%04d".format(counter),
                    ordTmd = "100000",
                ),
            )
        }
    }

    private fun stubSubmitOrderFail() {
        every { kisRestClient.requestOrder(any(), any(), any()) } throws RestClientException("4xx")
    }

    private fun simulateFill(order: Order, qty: Int) {
        order.filledQty = qty
        order.status = OrderStatus.FILLED
        orderRepository.save(order)
    }

    private fun simulateBuyFillWithExecution(order: Order, qty: Int, price: Int) {
        simulateFill(order, qty)
        executionRepository.save(
            at.backend.trading.domain.execution.Execution(
                orderId = order.id,
                executedQty = qty,
                executedPrice = price,
                fee = 0,
                tax = 0,
            )
        )
    }

    private suspend fun waitUntilCycle(
        cycleId: Long,
        timeoutMillis: Long = 2000,
        predicate: (TradingCycle) -> Boolean
    ) {
        withTimeout(timeoutMillis.milliseconds) {
            while (!predicate(cycleRepository.findById(cycleId).get())) delay(20)
        }
    }

    init {
        beforeEach {
            clearMocks(kisRestClient, answers = false)
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
        }

        context("매수 흐름") {
            test("매수 체결되면 HOLDING으로 전이된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrderOk()
                val cycle = saveCycle()
                val target = runner(cycle)

                target.start()
                withTimeout(2000.milliseconds) {
                    var orders = emptyList<Order>()
                    while (orders.isEmpty()) {
                        delay(20.milliseconds)
                        orders = orderRepository.findByCycleId(cycle.id).filter { it.side == OrderSide.BUY }
                        orders.filter { it.filledQty == 0 }.forEach { simulateFill(it, it.orderQty) }
                    }
                }
                waitUntilCycle(cycle.id) { it.status == TradingCycleStatus.HOLDING }
                target.cancel()

                val refreshed = cycleRepository.findById(cycle.id).get()
                refreshed.status shouldBe TradingCycleStatus.HOLDING
            }

            test("매수 발송 실패면 CLOSED(NO_FILL)로 종료된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrderFail()
                val cycle = saveCycle()
                val target = runner(cycle)

                target.start()
                waitUntilCycle(cycle.id) { it.status == TradingCycleStatus.CLOSED }
                target.cancel()

                val refreshed = cycleRepository.findById(cycle.id).get()
                refreshed.status shouldBe TradingCycleStatus.CLOSED
                refreshed.closeReason shouldBe CloseReason.NO_FILL
            }

            test("체결 통보가 도착하지 않으면 fill-wait 후 NO_FILL로 종료된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrderOk()
                val cycle = saveCycle()
                val target = runner(cycle)

                // 발송은 성공하지만 체결을 시뮬레이션하지 않음 → fill-wait 초과 후 NO_FILL
                target.start()
                waitUntilCycle(cycle.id, timeoutMillis = 3000) { it.status == TradingCycleStatus.CLOSED }
                target.cancel()

                val refreshed = cycleRepository.findById(cycle.id).get()
                refreshed.closeReason shouldBe CloseReason.NO_FILL
            }
        }

        context("매도 시그널 + 종료 흐름") {
            suspend fun reachHoldingFullyFilled(cycle: TradingCycle, target: TradingCycleRunner, fillPrice: Int) {
                target.start()
                withTimeout(2000.milliseconds) {
                    var orders = emptyList<Order>()
                    while (orders.isEmpty()) {
                        delay(10.milliseconds)
                        orders = orderRepository.findByCycleId(cycle.id).filter { it.side == OrderSide.BUY }
                        orders.filter { it.filledQty == 0 }
                            .forEach { simulateBuyFillWithExecution(it, it.orderQty, fillPrice) }
                    }
                }
                waitUntilCycle(cycle.id) { it.status == TradingCycleStatus.HOLDING }
            }

            suspend fun emitTick(price: Int) {
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
                channels.priceTicks.emit(at.backend.market.domain.PriceTick("005930", price, java.time.Instant.now()))
            }

            test("StopLoss 가격 진입 시 매도 주문이 발사되고 LIQUIDATING/STOP_LOSS로 종료된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrderOk()
                val cycle = saveCycle()
                val target = runner(cycle)
                reachHoldingFullyFilled(cycle, target, fillPrice = 70_000)

                emitTick(60_000)

                waitUntilCycle(cycle.id, timeoutMillis = 3000) {
                    orderRepository.findByCycleId(cycle.id).any { it.side == OrderSide.SELL }
                }
                val sell = orderRepository.findByCycleId(cycle.id).first { it.side == OrderSide.SELL }
                simulateFill(sell, sell.orderQty)
                waitUntilCycle(cycle.id, timeoutMillis = 3000) { it.status == TradingCycleStatus.CLOSED }
                target.cancel()

                val refreshed = cycleRepository.findById(cycle.id).get()
                refreshed.status shouldBe TradingCycleStatus.CLOSED
                refreshed.closeReason shouldBe CloseReason.STOP_LOSS
            }

            test("Breakeven 임계 가격 도달 시 breakevenArmed가 true로 갱신된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrderOk()
                val cycle = saveCycle()
                val target = runner(cycle)
                reachHoldingFullyFilled(cycle, target, fillPrice = 70_000)

                emitTick(80_000)

                waitUntilCycle(cycle.id, timeoutMillis = 3000) { it.breakevenArmed }
                target.cancel()

                cycleRepository.findById(cycle.id).get().breakevenArmed shouldBe true
            }

        }
    }
}
