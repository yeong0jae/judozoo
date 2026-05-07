package at.backend.report.application

import at.backend.common.test.IntegrationTestBase
import at.backend.trading.domain.cycle.CloseReason
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.cycle.TradingCycleStatus
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.order.OrderStatus
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import at.backend.trading.infrastructure.repository.TradingCycleJpaRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class ReportServiceTest(
    @Autowired private val reportService: ReportService,
    @Autowired private val cycleRepository: TradingCycleJpaRepository,
    @Autowired private val orderRepository: OrderJpaRepository,
    @Autowired private val executionRepository: ExecutionJpaRepository,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val txTemplate: TransactionTemplate,
) : IntegrationTestBase() {

    init {
        beforeEach {
            executionRepository.deleteAll()
            orderRepository.deleteAll()
            cycleRepository.deleteAll()
        }

        context("일별 실적 집계") {

            test("매수/매도 체결을 합산해 순수익을 계산한다") {
                val date = LocalDate.of(2026, 3, 14)
                val cycleId = saveCycle(date.atTime(9, 30), CloseReason.TAKE_PROFIT)
                val buyId = saveOrder(cycleId, side = OrderSide.BUY, trigger = "INITIAL", qty = 100)
                val sellId = saveOrder(cycleId, side = OrderSide.SELL, trigger = "TP_2PCT", qty = 100)
                executionRepository.save(execution(buyId, qty = 100, price = 1_000, fee = 100, tax = 0))
                executionRepository.save(execution(sellId, qty = 100, price = 1_050, fee = 105, tax = 100))

                val result = reportService.findDaily(date)

                result shouldHaveSize 1
                val r = result[0]
                r.avgBuyPrice shouldBe 1_000
                r.avgSellPrice shouldBe 1_050
                r.totalFee shouldBe 205
                r.totalTax shouldBe 100
                r.grossProfit shouldBe 5_000
                r.netProfit shouldBe 4_695
                r.profitRate shouldBe (4_695.0 / 100_000)
            }

            test("매수만 있고 매도가 없으면 평단 매도가는 null, 순수익은 음수") {
                val date = LocalDate.of(2026, 3, 14)
                val cycleId = saveCycle(date.atTime(9, 30), CloseReason.NO_FILL)
                val buyId = saveOrder(cycleId, side = OrderSide.BUY, trigger = "INITIAL", qty = 50)
                executionRepository.save(execution(buyId, qty = 50, price = 2_000, fee = 50, tax = 0))

                val r = reportService.findDaily(date).first()

                r.avgBuyPrice shouldBe 2_000
                r.avgSellPrice shouldBe null
                r.grossProfit shouldBe -100_000
                r.netProfit shouldBe -100_050
            }

            test("date 외 사이클은 결과에 포함되지 않는다") {
                val day = LocalDate.of(2026, 3, 14)
                saveCycle(day.minusDays(1).atTime(15, 0), CloseReason.TAKE_PROFIT)
                saveCycle(day.atTime(10, 0), CloseReason.STOP_LOSS)
                saveCycle(day.plusDays(1).atTime(9, 0), CloseReason.TAKE_PROFIT)

                val result = reportService.findDaily(day)

                result shouldHaveSize 1
            }
        }
    }

    private fun saveCycle(createdAt: LocalDateTime, closeReason: CloseReason): Long {
        val cycle = TradingCycle(
            stockCode = "005930",
            stockName = "삼성전자",
            perBuyAmount = 100_000,
            buyIntervalMin = 3,
            splitSellRatio = BigDecimal("0.20"),
            midwayProfitPct = BigDecimal("3.0"),
            breakevenThresholdPct = BigDecimal("2.0"),
            stopLossPct = BigDecimal("-2.0"),
            status = TradingCycleStatus.CLOSED,
            closeReason = closeReason,
            closedAt = createdAt.plusHours(1),
        )
        val saved = cycleRepository.save(cycle)
        cycleRepository.flush()
        // @CreationTimestamp는 INSERT 시점 시계 — 테스트에서는 native UPDATE로 강제
        txTemplate.execute {
            val q = entityManager.createNativeQuery("UPDATE trading_cycles SET created_at = ? WHERE id = ?")
            q.setParameter(1, createdAt)
            q.setParameter(2, saved.id)
            q.executeUpdate()
        }
        entityManager.clear()
        return saved.id
    }

    private fun saveOrder(cycleId: Long, side: OrderSide, trigger: String, qty: Int): Long =
        orderRepository.save(
            Order(
                cycleId = cycleId,
                side = side,
                trigger = trigger,
                orderQty = qty,
                status = OrderStatus.FILLED,
                kisOrderNo = "K${System.nanoTime()}".take(20),
            )
        ).id

    private fun execution(orderId: Long, qty: Int, price: Int, fee: Int, tax: Int): Execution =
        Execution(orderId = orderId, executedQty = qty, executedPrice = price, fee = fee, tax = tax)
}
