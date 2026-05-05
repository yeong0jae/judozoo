package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.market.domain.PriceTick
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.KisWebSocketClient
import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import at.backend.trading.domain.TradingInput
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.order.Order
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

@Import(KisRestClientMockConfig::class, KisWebSocketClientMockConfig::class, FixedTimeProviderConfig::class)
class TradingCycleScenarioTest(
    @Autowired private val tradingService: TradingService,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val executionRepository: ExecutionJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val webSocketClient: KisWebSocketClient,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private val stockCode = "005930"

    private fun stubSearchStock(name: String = "삼성전자") {
        every { kisRestClient.searchStock(any()) } returns KisStockSearchResponse(
            output = listOf(KisStockSearchResponse.Output(pdno = stockCode, prdtAbrvName = name))
        )
    }

    private fun stubCurrentPrice(price: Int = 70_000) {
        every { kisRestClient.getCurrentPrice(any()) } returns KisCurrentPriceResponse(
            output = KisCurrentPriceResponse.Output(stckPrpr = price.toString())
        )
    }

    private fun stubBalance(amount: Long = 100_000_000) {
        every { kisRestClient.getBalance() } returns KisBalanceResponse(
            output2 = listOf(KisBalanceResponse.Output(prvsRcdlExccAmt = amount.toString()))
        )
    }

    private fun stubHoliday() {
        every { kisRestClient.checkHoliday(any()) } returns KisHolidayResponse(
            output = listOf(KisHolidayResponse.Output(bzdyYn = "Y"))
        )
    }

    private fun stubSubmitOrderOk() {
        var counter = 0
        every { kisRestClient.submitOrder(any(), any(), any()) } answers {
            counter += 1
            KisOrderResponse(
                rtCd = "0", msgCd = "OK", msg1 = "OK",
                output = KisOrderResponse.Output(
                    krxFwdgOrdOrgno = "00950",
                    odno = "ODNO%04d".format(counter),
                    ordTmd = "100000",
                ),
            )
        }
    }

    private fun stubSubmitOrderFail() {
        every { kisRestClient.submitOrder(any(), any(), any()) } throws
                org.springframework.web.client.RestClientException("4xx")
    }

    private fun stubKisDefaults() {
        stubSearchStock()
        stubCurrentPrice()
        stubBalance()
        stubHoliday()
        stubSubmitOrderOk()
    }

    private fun validInput(perBuyAmount: Long = 1_000_000) = TradingInput(
        stockCode = stockCode,
        perBuyAmount = perBuyAmount,
        buyIntervalMin = 1,
        splitSellRatio = BigDecimal("0.5"),
        midwayProfitPct = BigDecimal("3.0"),
        breakevenThresholdPct = BigDecimal("2.0"),
        stopLossPct = BigDecimal("2.0"),
    )

    private fun fillBuyOrder(order: Order, qty: Int, price: Int) {
        order.filledQty = qty
        order.status = "FILLED"
        orderRepository.save(order)
        executionRepository.save(
            Execution(
                orderId = order.id,
                executedQty = qty,
                executedPrice = price,
                fee = 0,
                tax = 0,
            )
        )
    }

    private fun fillSellOrder(order: Order, qty: Int) {
        order.filledQty = qty
        order.status = "FILLED"
        orderRepository.save(order)
    }

    private fun fillAllPendingBuys(cycleId: Long, fillPrice: Int) {
        orderRepository.findByCycleId(cycleId)
            .filter { it.side == "BUY" && it.filledQty == 0 && it.status == "PENDING" && it.kisOrderNo != null }
            .forEach { fillBuyOrder(it, it.orderQty, fillPrice) }
    }

    private suspend fun emitTick(price: Int) {
        val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)
        channels.priceTicks.emit(PriceTick(stockCode, price, Instant.now()))
    }

    /**
     * SharedFlow는 replay=0 + 구독 시작 전 emit이 lost되므로, 조건 충족까지 반복 emit.
     */
    private suspend fun emitTicksUntil(price: Int, timeoutMillis: Long = 5000, condition: () -> Boolean) {
        withTimeout(timeoutMillis.milliseconds) {
            while (!condition()) {
                emitTick(price)
                delay(50)
            }
        }
    }

    private suspend fun waitUntilCycle(
        cycleId: Long,
        timeoutMillis: Long = 5000,
        predicate: (TradingCycle) -> Boolean,
    ) {
        withTimeout(timeoutMillis.milliseconds) {
            while (!predicate(cycleRepository.findById(cycleId).get())) delay(20)
        }
    }

    private suspend fun waitUntilOrders(
        cycleId: Long,
        timeoutMillis: Long = 5000,
        predicate: (List<Order>) -> Boolean,
    ) {
        withTimeout(timeoutMillis.milliseconds) {
            while (!predicate(orderRepository.findByCycleId(cycleId))) delay(20)
        }
    }

    private suspend fun reachHoldingFullyFilled(cycleId: Long, fillPrice: Int) {
        withTimeout(5000.milliseconds) {
            while (true) {
                val buys = orderRepository.findByCycleId(cycleId).filter { it.side == "BUY" }
                fillAllPendingBuys(cycleId, fillPrice)
                if (buys.size == 3 && buys.all { it.filledQty > 0 }) break
                delay(20)
            }
        }
        waitUntilCycle(cycleId) { it.status == TradingCycleStatus.HOLDING }
    }

    init {
        beforeEach {
            clearMocks(kisRestClient, answers = false)
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
            stubKisDefaults()
        }

        context("사이클 시나리오") {
            test("부분 체결/NO_FILL: 매수 3회 모두 발송 실패 시 CLOSED(NO_FILL)로 종료") {
                stubSubmitOrderFail()

                val created = tradingService.create(validInput())

                runBlocking {
                    waitUntilCycle(created.id) { it.status == TradingCycleStatus.CLOSED }
                }

                val refreshed = cycleRepository.findById(created.id).get()
                refreshed.status shouldBe TradingCycleStatus.CLOSED
                refreshed.closeReason shouldBe CloseReason.NO_FILL

                val buyOrders = orderRepository.findByCycleId(created.id).filter { it.side == "BUY" }
                buyOrders.size shouldBe 3
                buyOrders.all { it.filledQty == 0 } shouldBe true
            }
        }
    }
}
