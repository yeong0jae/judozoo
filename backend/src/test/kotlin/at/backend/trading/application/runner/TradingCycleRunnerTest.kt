package at.backend.trading.application.runner

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.trading.application.OrderExecutor
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.Order
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

@Import(KisRestClientMockConfig::class, FixedTimeProviderConfig::class)
class TradingCycleRunnerTest(
    @Autowired private val orderExecutor: OrderExecutor,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun saveCycle(buyIntervalMin: Int = 3, perBuyAmount: Long = 1_000_000): TradingCycle =
        cycleRepository.save(
            TradingCycle(
                stockCode = "005930",
                stockName = "삼성전자",
                perBuyAmount = perBuyAmount,
                buyIntervalMin = buyIntervalMin,
                splitSellRatio = BigDecimal("0.5"),
                midwayProfitPct = BigDecimal("3.0"),
                breakevenThresholdPct = BigDecimal("2.0"),
                stopLossPct = BigDecimal("-0.02"),
                status = TradingCycleStatus.INITIATED,
                buyAttempt = 0,
            )
        )

    private fun runner(cycle: TradingCycle) = TradingCycleRunner(
        cycle = cycle,
        applicationScope = applicationScope,
        orderExecutor = orderExecutor,
        cycleRepository = cycleRepository,
        orderRepository = orderRepository,
        timeProvider = timeProvider,
        buyIntervalUnit = 5.milliseconds,
    )

    private fun stubCurrentPrice(price: Int) {
        every { kisRestClient.getCurrentPrice(any()) } returns
                KisCurrentPriceResponse(KisCurrentPriceResponse.Output(stckPrpr = price.toString()))
    }

    private fun stubSubmitOrderOk() {
        var counter = 0
        every { kisRestClient.submitOrder(any(), any(), any()) } answers {
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
        every { kisRestClient.submitOrder(any(), any(), any()) } throws RestClientException("4xx")
    }

    private fun simulateFill(order: Order, qty: Int) {
        order.filledQty = qty
        order.status = "FILLED"
        orderRepository.save(order)
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

        context("매수 회차 흐름") {
            test("3회 매수 모두 정상 체결되면 HOLDING으로 전이된다") {
                stubCurrentPrice(70_000)
                stubSubmitOrderOk()
                val cycle = saveCycle()
                val target = runner(cycle)

                target.start()
                withTimeout(2000.milliseconds) {
                    var orders = emptyList<Order>()
                    while (orders.size < 3) {
                        delay(20.milliseconds)
                        orders = orderRepository.findByCycleId(cycle.id).filter { it.side == "BUY" }
                        orders.filter { it.filledQty == 0 }.forEach { simulateFill(it, it.orderQty) }
                    }
                }
                waitUntilCycle(cycle.id) { it.status == TradingCycleStatus.HOLDING }
                target.cancel()

                val refreshed = cycleRepository.findById(cycle.id).get()
                refreshed.status shouldBe TradingCycleStatus.HOLDING
                refreshed.buyAttempt shouldBe 3
            }

            test("모든 회차 발송 실패면 CLOSED(NO_FILL)로 종료된다") {
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

            test("발송 실패 회차도 회차 카운트는 진행되어 다음 회차가 시도된다") {
                stubCurrentPrice(70_000)
                var attempt = 0
                every { kisRestClient.submitOrder(any(), any(), any()) } answers {
                    attempt += 1
                    if (attempt == 1) {
                        throw RestClientException("4xx")
                    } else {
                        KisOrderResponse(
                            rtCd = "0", msgCd = "APBK0013", msg1 = "OK",
                            output = KisOrderResponse.Output(
                                krxFwdgOrdOrgno = "00950",
                                odno = "ODNO_$attempt",
                                ordTmd = "100000",
                            ),
                        )
                    }
                }
                val cycle = saveCycle()
                val target = runner(cycle)

                target.start()
                withTimeout(2000.milliseconds) {
                    var orders = emptyList<Order>()
                    while (orders.size < 3) {
                        delay(20.milliseconds)
                        orders = orderRepository.findByCycleId(cycle.id).filter { it.side == "BUY" }
                        orders.filter { it.status == "PENDING" && it.filledQty == 0 }
                            .forEach { simulateFill(it, it.orderQty) }
                    }
                }
                waitUntilCycle(cycle.id) { it.status == TradingCycleStatus.HOLDING }
                target.cancel()

                val orders = orderRepository.findByCycleId(cycle.id).filter { it.side == "BUY" }
                orders shouldHaveSize 3
                attempt shouldBe 3
            }
        }
    }
}
