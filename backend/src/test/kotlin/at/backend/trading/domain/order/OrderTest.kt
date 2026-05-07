package at.backend.trading.domain.order

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class OrderTest : FunSpec({

    fun pendingBuy(orderQty: Int = 10, orderNo: String? = "ODNO0001") = Order(
        id = 1L,
        cycleId = 100L,
        side = OrderSide.BUY,
        trigger = "BUY_1",
        orderQty = orderQty,
        orderNo = orderNo,
        status = OrderStatus.PENDING,
    )

    fun notice(qty: Int, price: Int = 70_000, orderNo: String = "ODNO0001", side: OrderSide = OrderSide.BUY) =
        ExecutionNotice(
            orderNo = orderNo,
            stockCode = "005930",
            side = side,
            executedQty = qty,
            executedPrice = price,
            timestamp = Instant.parse("2026-01-02T01:00:00Z"),
        )

    context("체결 통보 적용") {
        test("부분 체결 통보를 받으면 filledQty가 누적되고 상태는 PENDING 유지") {
            val order = pendingBuy(orderQty = 10)

            val execution = order.applyExecution(notice(qty = 4), fee = 100, tax = 0)

            order.filledQty shouldBe 4
            order.status shouldBe OrderStatus.PENDING
            order.isFullyFilled() shouldBe false
            execution.executedQty shouldBe 4
            execution.executedPrice shouldBe 70_000
            execution.fee shouldBe 100
            execution.orderId shouldBe order.id
        }

        test("연속 통보로 전량 체결되면 상태가 FILLED로 전이된다") {
            val order = pendingBuy(orderQty = 10)

            order.applyExecution(notice(qty = 4), fee = 0, tax = 0)
            order.applyExecution(notice(qty = 6), fee = 0, tax = 0)

            order.filledQty shouldBe 10
            order.status shouldBe OrderStatus.FILLED
            order.isFullyFilled() shouldBe true
        }

        test("FILLED 이후 추가 통보가 와도 누적은 계속되고 상태는 FILLED 유지") {
            val order = pendingBuy(orderQty = 10)
            order.applyExecution(notice(qty = 10), fee = 0, tax = 0)

            order.applyExecution(notice(qty = 1), fee = 0, tax = 0)

            order.filledQty shouldBe 11
            order.status shouldBe OrderStatus.FILLED
        }

        test("주문번호가 다른 통보는 거부된다") {
            val order = pendingBuy(orderNo = "ODNO0001")

            shouldThrow<IllegalArgumentException> {
                order.applyExecution(notice(qty = 1, orderNo = "ODNO9999"), fee = 0, tax = 0)
            }
        }

        test("side가 다른 통보는 거부된다") {
            val order = pendingBuy()

            shouldThrow<IllegalArgumentException> {
                order.applyExecution(notice(qty = 1, side = OrderSide.SELL), fee = 0, tax = 0)
            }
        }
    }
})
