package at.backend.trading.domain.signal

import at.backend.trading.domain.Bar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class SignalTest : FunSpec({

    val buyPrice = 10_000
    val now = Instant.now()

    context("한 번 발동하면 끝까지 유효한 시그널들") {
        test("StopLoss는 항상 alive") {
            Signal.StopLoss.isAlive(currentPrice = 9_000, buyPrice, currentBar = null, clock = now) shouldBe true
        }

        test("Cancel은 항상 alive") {
            Signal.Cancel.isAlive(currentPrice = 9_000, buyPrice, currentBar = null, clock = now) shouldBe true
        }

        test("MarketClose는 항상 alive") {
            Signal.MarketClose.isAlive(currentPrice = 9_000, buyPrice, currentBar = null, clock = now) shouldBe true
        }

        test("MidwayTakeProfit은 항상 alive") {
            Signal.MidwayTakeProfit.isAlive(currentPrice = 10_500, buyPrice, currentBar = null, clock = now) shouldBe true
        }

        test("TpStage는 항상 alive") {
            Signal.TpStage(2).isAlive(currentPrice = 10_200, buyPrice, currentBar = null, clock = now) shouldBe true
        }

        test("LimitUp은 항상 alive") {
            Signal.LimitUp.isAlive(currentPrice = 13_000, buyPrice, currentBar = null, clock = now) shouldBe true
        }
    }

    context("Breakeven은 매수가 이하 유지 중에만 유효") {
        test("현재가가 매수가 이하이면 alive") {
            Signal.Breakeven.isAlive(currentPrice = 10_000, buyPrice, currentBar = null, clock = now) shouldBe true
            Signal.Breakeven.isAlive(currentPrice = 9_999, buyPrice, currentBar = null, clock = now) shouldBe true
        }

        test("현재가가 매수가 위이면 dead — 회복 후 재진입 불필요") {
            Signal.Breakeven.isAlive(currentPrice = 10_001, buyPrice, currentBar = null, clock = now) shouldBe false
        }
    }

    context("TpStage 불변식") {
        test("유효하지 않은 단계 비율이면 예외") {
            shouldThrow<IllegalArgumentException> { Signal.TpStage(1) }
            shouldThrow<IllegalArgumentException> { Signal.TpStage(4) }
            shouldThrow<IllegalArgumentException> { Signal.TpStage(0) }
        }
    }

    context("TrendBreak은 발동된 봉이 진행 중일 때만 유효") {
        val barInProgress = Bar(
            stockCode = "000660",
            openPrice = 10_600,
            closePrice = 10_400,
            startTime = now.minusSeconds(120),
            endTime = now.plusSeconds(60),
        )
        val barFinished = Bar(
            stockCode = "000660",
            openPrice = 10_600,
            closePrice = 10_400,
            startTime = now.minusSeconds(300),
            endTime = now.minusSeconds(120),
        )

        test("봉이 진행 중이면 alive") {
            Signal.TrendBreak.isAlive(currentPrice = 10_400, buyPrice, currentBar = barInProgress, clock = now) shouldBe true
        }

        test("봉이 종료됐으면 dead — 다음 봉에서 재발동 대기") {
            Signal.TrendBreak.isAlive(currentPrice = 10_400, buyPrice, currentBar = barFinished, clock = now) shouldBe false
        }

        test("봉 정보 없으면 dead") {
            Signal.TrendBreak.isAlive(currentPrice = 10_400, buyPrice, currentBar = null, clock = now) shouldBe false
        }
    }
})
