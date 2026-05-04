package at.backend.market.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import java.time.Instant

class BarTest : FunSpec({

    val now = Instant.now()

    context("불변식") {
        test("종목코드가 공백이면 예외") {
            shouldThrow<IllegalArgumentException> {
                Bar("  ", openPrice = 10_000, closePrice = 10_500, startTime = now.minusSeconds(180), endTime = now)
            }
        }

        test("시가가 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> {
                Bar("000660", openPrice = 0, closePrice = 10_500, startTime = now.minusSeconds(180), endTime = now)
            }
        }

        test("종가가 0 이하이면 예외") {
            shouldThrow<IllegalArgumentException> {
                Bar("000660", openPrice = 10_000, closePrice = 0, startTime = now.minusSeconds(180), endTime = now)
            }
        }

        test("시작 시각이 종료 시각과 같거나 이후이면 예외") {
            shouldThrow<IllegalArgumentException> {
                Bar("000660", openPrice = 10_000, closePrice = 10_500, startTime = now, endTime = now)
            }
            shouldThrow<IllegalArgumentException> {
                Bar("000660", openPrice = 10_000, closePrice = 10_500, startTime = now.plusSeconds(1), endTime = now)
            }
        }
    }
})
