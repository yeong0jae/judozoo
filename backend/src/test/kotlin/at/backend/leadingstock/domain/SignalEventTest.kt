package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

class SignalEventTest : FunSpec({

    fun event(type: SignalEventType, direction: SpikeDirection? = null) =
        SignalEvent(
            occurredAt = LocalDateTime.of(2026, 6, 27, 10, 0),
            tradeDate = LocalDate.of(2026, 6, 27),
            stockCode = "000660",
            stockName = "에스케이하이닉스",
            eventType = type,
            currentPrice = 1000,
            priceChangeRate = 0.0,
            tradingValue = 0,
            spikeDirection = direction,
        )

    context("신호 종류 분류") {
        test("돌파·임박은 그대로 매핑된다") {
            event(SignalEventType.BREAKOUT).kind() shouldBe SignalKind.BREAKOUT
            event(SignalEventType.BREAKOUT_IMMINENT).kind() shouldBe SignalKind.BREAKOUT_IMMINENT
        }

        test("매수 스파이크는 진입 후보로, 매도 스파이크는 회피 종류로 가른다") {
            event(SignalEventType.VOLUME_SPIKE, SpikeDirection.BUY).kind() shouldBe SignalKind.SPIKE_BUY
            event(SignalEventType.VOLUME_SPIKE, SpikeDirection.SELL).kind() shouldBe SignalKind.SPIKE_SELL
        }

        test("방향이 보합이거나 없는 스파이크는 보합 종류") {
            event(SignalEventType.VOLUME_SPIKE, SpikeDirection.FLAT).kind() shouldBe SignalKind.SPIKE_FLAT
            event(SignalEventType.VOLUME_SPIKE, null).kind() shouldBe SignalKind.SPIKE_FLAT
        }
    }
})
