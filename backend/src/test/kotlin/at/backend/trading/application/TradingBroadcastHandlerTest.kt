package at.backend.trading.application

import at.backend.trading.domain.event.CycleStateChanged
import at.backend.trading.domain.event.OrderExecuted
import at.backend.trading.domain.event.PriceUpdated
import at.backend.trading.domain.event.RetryAccumulated
import at.backend.trading.domain.event.SignalArmed
import at.backend.trading.domain.event.SignalFired
import at.backend.trading.domain.event.TradingCycleClosed
import at.backend.trading.domain.event.TradingCycleCreated
import at.backend.trading.presentation.payload.LifecyclePayload
import at.backend.trading.presentation.payload.TradingPayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.Instant

class TradingBroadcastHandlerTest : FunSpec({

    val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    val handler = TradingBroadcastHandler(messagingTemplate)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("TradingCycleCreated 이벤트는 lifecycle 토픽 CREATED 페이로드로 변환된다") {
        handler.onCycleCreated(TradingCycleCreated(11L, "005930", "삼성전자", ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/lifecycle",
                LifecyclePayload.Created(11L, "005930", "삼성전자", ts),
            )
        }
    }

    test("TradingCycleClosed 이벤트는 lifecycle 토픽 CLOSED 페이로드로 변환된다") {
        handler.onCycleClosed(TradingCycleClosed(22L, "STOP_LOSS", ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/lifecycle",
                LifecyclePayload.Closed(22L, "STOP_LOSS", ts),
            )
        }
    }

    test("PriceUpdated 이벤트는 사이클별 토픽 PRICE 페이로드로 변환된다") {
        handler.onPriceUpdated(PriceUpdated(33L, 70_500, BigDecimal("0.500"), 10_000L, ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/33",
                TradingPayload.Price(70_500, BigDecimal("0.500"), 10_000L, ts),
            )
        }
    }

    test("CycleStateChanged 이벤트는 사이클별 토픽 STATE 페이로드로 변환된다") {
        handler.onCycleStateChanged(CycleStateChanged(44L, "HOLDING", null, ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/44",
                TradingPayload.State("HOLDING", null, ts),
            )
        }
    }

    test("SignalArmed 이벤트는 사이클별 토픽 SIGNAL ARMED 페이로드로 변환된다") {
        handler.onSignalArmed(SignalArmed(55L, "Breakeven", ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/55",
                TradingPayload.Signal("Breakeven", "ARMED", null, ts),
            )
        }
    }

    test("SignalFired 이벤트는 사이클별 토픽 SIGNAL FIRED 페이로드로 변환된다") {
        handler.onSignalFired(SignalFired(66L, "TpStage", 5, ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/66",
                TradingPayload.Signal("TpStage", "FIRED", 5, ts),
            )
        }
    }

    test("OrderExecuted 이벤트는 사이클별 토픽 EXECUTION 페이로드로 변환된다") {
        handler.onOrderExecuted(OrderExecuted(77L, "BUY", 14, 70_000, 14, 14, 70_000, ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/77",
                TradingPayload.Execution("BUY", 14, 70_000, 14, 14, 70_000, ts),
            )
        }
    }

    test("RetryAccumulated 이벤트는 사이클별 토픽 RETRY 페이로드로 변환된다") {
        handler.onRetryAccumulated(RetryAccumulated(88L, "Breakeven", 2, "timeout", ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/88",
                TradingPayload.Retry("Breakeven", 2, "timeout", ts),
            )
        }
    }
})
