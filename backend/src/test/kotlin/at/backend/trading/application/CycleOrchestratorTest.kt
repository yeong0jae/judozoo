package at.backend.trading.application

import at.backend.common.test.FixedTimeProviderConfig
import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.common.test.MutableTimeProvider
import at.backend.market.application.MarketDataStream
import at.backend.market.application.BarPoller
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisOrderResponse
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.order.OrderStatus
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

@Import(KisRestClientMockConfig::class, KisWebSocketClientMockConfig::class, FixedTimeProviderConfig::class)
class CycleOrchestratorTest(
    @Autowired private val orchestrator: CycleOrchestrator,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val kisRestClient: KisRestClient,
    @Autowired private val marketDataStream: MarketDataStream,
    @Autowired private val barPoller: BarPoller,
    @Autowired private val timeProvider: MutableTimeProvider,
) : IntegrationTestBase() {

    private fun saveCycle(
        stockCode: String = "005930",
        status: TradingCycleStatus = TradingCycleStatus.INITIATED,
        buyIntervalMin: Int = 1,
    ): TradingCycle = cycleRepository.save(
        TradingCycle(
            stockCode = stockCode,
            stockName = "삼성전자",
            perBuyAmount = 1_000_000L,
            buyIntervalMin = buyIntervalMin,
            splitSellRatio = BigDecimal("0.5"),
            midwayProfitPct = BigDecimal("3.0"),
            breakevenThresholdPct = BigDecimal("2.0"),
            stopLossPct = BigDecimal("-2.0"),
            status = status,
            buyAttempt = 0,
        )
    )

    private fun stubCurrentPrice(price: Int = 70_000) {
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

    private fun stubCancelOk() {
        every { kisRestClient.cancelRemainder(any(), any()) } returns
                KisOrderResponse(
                    rtCd = "0",
                    msgCd = "OK",
                    msg1 = "OK",
                    output = KisOrderResponse.Output(
                        krxFwdgOrdOrgno = "00950",
                        odno = "ODNO0001",
                        ordTmd = "100000",
                    ),
                )
    }

    private suspend fun waitFor(timeoutMillis: Long = 2000, predicate: () -> Boolean) {
        withTimeout(timeoutMillis.milliseconds) {
            while (!predicate()) delay(20)
        }
    }

    init {
        beforeEach {
            clearMocks(kisRestClient, answers = false)
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
            timeProvider.current = FixedTimeProviderConfig.DEFAULT_NOW
            marketDataStream.reset()
            barPoller.reset()
            stubCurrentPrice()
            stubSubmitOrderOk()
            stubCancelOk()
        }

        context("사이클 시작 / 자연 종료") {
            test("start 호출 시 시세/봉 스트림에 종목이 구독된다") {
                val cycle = saveCycle()

                orchestrator.start(cycle)

                marketDataStream.activeStockCodes() shouldContain "005930"
                orchestrator.activeCycleIds() shouldContain cycle.id
            }

            test("매수 전량 미체결로 자연 종료되면 구독이 해제된다") {
                every { kisRestClient.submitOrder(any(), any(), any()) } throws RuntimeException("4xx")
                val cycle = saveCycle()

                orchestrator.start(cycle)

                runBlocking {
                    waitFor(timeoutMillis = 5000) {
                        cycleRepository.findById(cycle.id).get().status == TradingCycleStatus.CLOSED
                    }
                    waitFor(timeoutMillis = 2000) { cycle.id !in orchestrator.activeCycleIds() }
                }

                marketDataStream.activeStockCodes() shouldNotContain "005930"
            }
        }

        context("사이클 취소") {
            test("취소 시 in-flight 매수가 KIS cancelRemainder로 정리된다") {
                val cycle = saveCycle()
                orchestrator.start(cycle)
                val pendingAtCancel = runBlocking {
                    waitFor(timeoutMillis = 3000) {
                        orderRepository.findByCycleId(cycle.id).any {
                            it.side == OrderSide.BUY && it.status == OrderStatus.PENDING && it.kisOrderNo != null
                        }
                    }
                    orderRepository.findByCycleId(cycle.id)
                        .filter { it.side == OrderSide.BUY && it.status == OrderStatus.PENDING && it.kisOrderNo != null }
                        .map { it.id }
                }

                orchestrator.cancel(cycle.id)

                verify(atLeast = 1) { kisRestClient.cancelRemainder(any(), any()) }
                runBlocking {
                    waitFor(timeoutMillis = 3000) {
                        pendingAtCancel.all { id ->
                            orderRepository.findById(id).get().status == OrderStatus.CANCELLED
                        }
                    }
                }
            }

            test("등록되지 않은 사이클 취소는 무시된다") {
                orchestrator.cancel(9999L)

                verify(exactly = 0) { kisRestClient.cancelRemainder(any(), any()) }
            }

            test("BUYING 단계 취소 시 매수 회차가 즉시 중단되어 보유분 없으면 CLOSED(CANCELLED)로 종료된다") {
                val cycle = saveCycle(buyIntervalMin = 200)
                orchestrator.start(cycle)
                runBlocking {
                    waitFor(timeoutMillis = 3000) {
                        orderRepository.findByCycleId(cycle.id).any {
                            it.side == OrderSide.BUY && it.kisOrderNo != null
                        }
                    }
                }

                orchestrator.cancel(cycle.id)

                runBlocking {
                    waitFor(timeoutMillis = 3000) {
                        cycleRepository.findById(cycle.id).get().status == TradingCycleStatus.CLOSED
                    }
                }
                val refreshed = cycleRepository.findById(cycle.id).get()
                refreshed.closeReason shouldBe CloseReason.CANCELLED

                val buyOrders = orderRepository.findByCycleId(cycle.id).filter { it.side == OrderSide.BUY }
                buyOrders shouldHaveSize 1
            }
        }

        context("정리 / 자연 종료 후 재시작") {
            test("자연 종료된 사이클은 activeCycleIds에서 제거된다") {
                every { kisRestClient.submitOrder(any(), any(), any()) } throws RuntimeException("4xx")
                val cycle = saveCycle()
                orchestrator.start(cycle)

                runBlocking {
                    waitFor(timeoutMillis = 5000) { cycle.id !in orchestrator.activeCycleIds() }
                }

                orchestrator.activeCycleIds() shouldHaveSize 0
            }
        }
    }
}
