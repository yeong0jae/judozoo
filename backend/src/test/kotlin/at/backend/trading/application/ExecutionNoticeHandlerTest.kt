package at.backend.trading.application

import at.backend.common.test.IntegrationTestBase
import at.backend.common.test.KisRestClientMockConfig
import at.backend.common.test.KisWebSocketClientMockConfig
import at.backend.platform.kis.client.KisWebSocketClient
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.order.ExecutionNotice
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.order.OrderStatus
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

@Import(KisWebSocketClientMockConfig::class, KisRestClientMockConfig::class)
class ExecutionNoticeHandlerTest(
    @Autowired private val handler: ExecutionNoticeHandler,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val executionRepository: ExecutionJpaRepository,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val webSocketClient: KisWebSocketClient,
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
            status = TradingCycleStatus.BUYING,
            buyAttempt = 1,
        )
    )

    private fun saveOrder(
        cycleId: Long,
        orderNo: String = "ODNO0001",
        orderQty: Int = 10,
        side: OrderSide = OrderSide.BUY,
    ): Order = orderRepository.save(
        Order(
            cycleId = cycleId,
            side = side,
            trigger = "BUY_1",
            orderQty = orderQty,
            orderNo = orderNo,
            fwdgOrdOrgno = "00950",
            status = OrderStatus.PENDING,
        )
    )

    private fun notice(
        orderNo: String = "ODNO0001",
        qty: Int,
        price: Int = 70_000,
        side: OrderSide = OrderSide.BUY,
    ) = ExecutionNotice(
        orderNo = orderNo,
        stockCode = "005930",
        side = side,
        executedQty = qty,
        executedPrice = price,
        timestamp = Instant.parse("2026-01-02T01:00:00Z"),
    )

    init {
        beforeEach {
            executionRepository.deleteAll()
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
        }

        context("체결 통보 처리") {
            test("부분 체결 통보가 누적되고 전량 체결 시 FILLED로 전이된다") {
                val cycle = saveCycle()
                val order = saveOrder(cycle.id, orderQty = 10)

                handler.handle(notice(qty = 4))
                handler.handle(notice(qty = 6))

                val refreshed = orderRepository.findById(order.id).get()
                refreshed.filledQty shouldBe 10
                refreshed.status shouldBe OrderStatus.FILLED
                executionRepository.findByOrderId(order.id) shouldHaveSize 2
            }

            test("일치하는 Order가 없는 통보는 무시된다") {
                val cycle = saveCycle()
                saveOrder(cycle.id, orderNo = "ODNO0001", orderQty = 10)

                handler.handle(notice(orderNo = "UNKNOWN", qty = 5))

                val orders = orderRepository.findByCycleId(cycle.id)
                orders.single().filledQty shouldBe 0
                executionRepository.count() shouldBe 0
            }

            test("동일 Order에 대한 다중 통보는 모두 Execution으로 저장된다") {
                val cycle = saveCycle()
                val order = saveOrder(cycle.id, orderQty = 10)

                handler.handle(notice(qty = 3, price = 70_000))
                handler.handle(notice(qty = 3, price = 70_100))
                handler.handle(notice(qty = 4, price = 70_200))

                val executions = executionRepository.findByOrderId(order.id).sortedBy { it.id }
                executions shouldHaveSize 3
                executions.map { it.executedPrice } shouldBe listOf(70_000, 70_100, 70_200)
                orderRepository.findById(order.id).get().status shouldBe OrderStatus.FILLED
            }
        }

        context("WebSocket 통보 스트림 연동") {
            test("KisWebSocketClient.executionNotices에 발행된 통보가 핸들러를 통해 반영된다") {
                val cycle = saveCycle()
                val order = saveOrder(cycle.id, orderQty = 10)
                val channels = KisWebSocketClientMockConfig.channelsOf(webSocketClient)

                channels.executionNotices.emit(notice(qty = 10))

                withTimeout(2000.milliseconds) {
                    while (orderRepository.findById(order.id).get().status != OrderStatus.FILLED) {
                        delay(20.milliseconds)
                    }
                }
                executionRepository.findByOrderId(order.id) shouldHaveSize 1
            }
        }
    }
}
