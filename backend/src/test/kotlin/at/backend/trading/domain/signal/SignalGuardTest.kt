package at.backend.trading.domain.signal

import at.backend.trading.domain.Bar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class SignalGuardTest : FunSpec({

    val buyPrice = 10_000
    val now = Instant.now()

    context("항상 alive인 시그널") {
        test("StopLoss는 항상 true") {
            SignalGuard.isAlive(Signal.StopLoss, currentPrice = 9_000, buyPrice, currentBar = null) shouldBe true
        }

        test("Cancel은 항상 true") {
            SignalGuard.isAlive(Signal.Cancel, currentPrice = 9_000, buyPrice, currentBar = null) shouldBe true
        }

        test("MarketClose는 항상 true") {
            SignalGuard.isAlive(Signal.MarketClose, currentPrice = 9_000, buyPrice, currentBar = null) shouldBe true
        }

        test("MidwayTakeProfit은 항상 true") {
            SignalGuard.isAlive(Signal.MidwayTakeProfit, currentPrice = 10_500, buyPrice, currentBar = null) shouldBe true
        }

        test("TpStage는 항상 true") {
            SignalGuard.isAlive(Signal.TpStage(2), currentPrice = 10_200, buyPrice, currentBar = null) shouldBe true
        }

        test("LimitUp은 항상 true") {
            SignalGuard.isAlive(Signal.LimitUp, currentPrice = 13_000, buyPrice, currentBar = null) shouldBe true
        }
    }

    context("Breakeven — 현재가 조건부") {
        test("현재가 ≤ 매수가이면 alive") {
            SignalGuard.isAlive(Signal.Breakeven, currentPrice = 10_000, buyPrice, currentBar = null) shouldBe true
            SignalGuard.isAlive(Signal.Breakeven, currentPrice = 9_999, buyPrice, currentBar = null) shouldBe true
        }

        test("현재가 > 매수가이면 dead — 매수가 회복 후 재진입 필요 없음") {
            SignalGuard.isAlive(Signal.Breakeven, currentPrice = 10_001, buyPrice, currentBar = null) shouldBe false
        }
    }

    context("TrendBreak — 봉 진행 여부 조건부") {
        val barInProgress = Bar(
            stockCode = "000660",
            openPrice = 10_600,
            closePrice = 10_400,
            startTime = now.minusSeconds(120),
            endTime = now.plusSeconds(60),  // 아직 60초 남음
        )
        val barFinished = Bar(
            stockCode = "000660",
            openPrice = 10_600,
            closePrice = 10_400,
            startTime = now.minusSeconds(300),
            endTime = now.minusSeconds(120),  // 이미 종료된 봉
        )

        test("봉이 아직 진행 중이면 alive") {
            SignalGuard.isAlive(Signal.TrendBreak, currentPrice = 10_400, buyPrice, currentBar = barInProgress, clock = now) shouldBe true
        }

        test("봉이 종료됐으면 dead — 다음 봉에서 재발동 대기") {
            SignalGuard.isAlive(Signal.TrendBreak, currentPrice = 10_400, buyPrice, currentBar = barFinished, clock = now) shouldBe false
        }

        test("currentBar가 null이면 dead") {
            SignalGuard.isAlive(Signal.TrendBreak, currentPrice = 10_400, buyPrice, currentBar = null) shouldBe false
        }
    }
})
