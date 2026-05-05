package at.backend.trading.presentation

import at.backend.trading.presentation.payload.LifecyclePayload
import at.backend.trading.presentation.payload.TradingPayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.Instant

class TradingBroadcasterTest : FunSpec({

    val template = mockk<SimpMessagingTemplate>(relaxed = true)
    val broadcaster = TradingBroadcaster(template)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("cycleCreated는 lifecycle 토픽 CREATED 페이로드를 발행한다") {
        broadcaster.cycleCreated(11L, "005930", "삼성전자", ts)
        verify {
            template.convertAndSend(
                "/topic/trading/lifecycle",
                LifecyclePayload.Created(11L, "005930", "삼성전자", ts),
            )
        }
    }

    test("cycleClosed는 lifecycle 토픽 CLOSED 페이로드를 발행한다") {
        broadcaster.cycleClosed(22L, "STOP_LOSS", ts)
        verify {
            template.convertAndSend(
                "/topic/trading/lifecycle",
                LifecyclePayload.Closed(22L, "STOP_LOSS", ts),
            )
        }
    }

    test("priceUpdated는 사이클별 토픽 PRICE 페이로드를 발행한다") {
        broadcaster.priceUpdated(33L, 70_500, BigDecimal("0.500"), 10_000L, ts)
        verify {
            template.convertAndSend(
                "/topic/trading/33",
                TradingPayload.Price(70_500, BigDecimal("0.500"), 10_000L, ts),
            )
        }
    }

    test("stateChanged는 사이클별 토픽 STATE 페이로드를 발행한다") {
        broadcaster.stateChanged(44L, "HOLDING", null, ts)
        verify {
            template.convertAndSend(
                "/topic/trading/44",
                TradingPayload.State("HOLDING", null, ts),
            )
        }
    }

    test("signalArmed는 사이클별 토픽 SIGNAL ARMED 페이로드를 발행한다") {
        broadcaster.signalArmed(55L, "Breakeven", ts)
        verify {
            template.convertAndSend(
                "/topic/trading/55",
                TradingPayload.Signal("Breakeven", "ARMED", null, ts),
            )
        }
    }

    test("signalFired는 사이클별 토픽 SIGNAL FIRED 페이로드를 발행한다") {
        broadcaster.signalFired(66L, "TpStage", 5, ts)
        verify {
            template.convertAndSend(
                "/topic/trading/66",
                TradingPayload.Signal("TpStage", "FIRED", 5, ts),
            )
        }
    }

    test("orderExecuted는 사이클별 토픽 EXECUTION 페이로드를 발행한다") {
        broadcaster.orderExecuted(77L, "BUY", 14, 70_000, 14, 14, 70_000, ts)
        verify {
            template.convertAndSend(
                "/topic/trading/77",
                TradingPayload.Execution("BUY", 14, 70_000, 14, 14, 70_000, ts),
            )
        }
    }

    test("retryAccumulated는 사이클별 토픽 RETRY 페이로드를 발행한다") {
        broadcaster.retryAccumulated(88L, "Breakeven", 2, "timeout", ts)
        verify {
            template.convertAndSend(
                "/topic/trading/88",
                TradingPayload.Retry("Breakeven", 2, "timeout", ts),
            )
        }
    }
})
