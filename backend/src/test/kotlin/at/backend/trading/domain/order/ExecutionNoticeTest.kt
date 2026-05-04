package at.backend.trading.domain.order

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ExecutionNoticeTest : FunSpec({

    val now = Instant.now()

    fun valid() = ExecutionNotice(
        kisOrderNo = "0000123456",
        stockCode = "005930",
        side = "BUY",
        executedQty = 10,
        executedPrice = 70_000,
        timestamp = now,
    )

    context("정상 생성") {
        test("BUY 통보를 만들 수 있다") {
            valid().side shouldBe "BUY"
        }

        test("SELL 통보를 만들 수 있다") {
            valid().copy(side = "SELL").side shouldBe "SELL"
        }
    }

    context("불변식") {
        test("주문번호가 공백이면 예외") {
            shouldThrow<IllegalArgumentException> { valid().copy(kisOrderNo = "   ") }
        }

        test("종목코드가 공백이면 예외") {
            shouldThrow<IllegalArgumentException> { valid().copy(stockCode = "") }
        }

        test("side가 BUY/SELL이 아니면 예외") {
            shouldThrow<IllegalArgumentException> { valid().copy(side = "BID") }
            shouldThrow<IllegalArgumentException> { valid().copy(side = "buy") }
        }

        test("체결 수량이 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> { valid().copy(executedQty = 0) }
            shouldThrow<IllegalArgumentException> { valid().copy(executedQty = -1) }
        }

        test("체결 단가가 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> { valid().copy(executedPrice = 0) }
            shouldThrow<IllegalArgumentException> { valid().copy(executedPrice = -100) }
        }
    }
})
