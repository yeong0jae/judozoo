package at.backend.trading.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import java.time.Instant

class PriceTickTest : FunSpec({

    val now = Instant.now()

    context("불변식") {
        test("종목코드가 공백이면 예외") {
            shouldThrow<IllegalArgumentException> {
                PriceTick("  ", price = 10_000, timestamp = now)
            }
        }

        test("현재가가 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> {
                PriceTick("000660", price = 0, timestamp = now)
            }
            shouldThrow<IllegalArgumentException> {
                PriceTick("000660", price = -1, timestamp = now)
            }
        }
    }
})
