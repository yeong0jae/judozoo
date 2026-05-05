package at.backend.trading.application

import at.backend.trading.domain.event.CycleStateChanged
import at.backend.trading.domain.event.OrderExecuted
import at.backend.trading.domain.event.PriceUpdated
import at.backend.trading.domain.event.RetryAccumulated
import at.backend.trading.domain.event.SignalArmed
import at.backend.trading.domain.event.SignalFired
import at.backend.trading.domain.event.TradingCycleClosed
import at.backend.trading.domain.event.TradingCycleCreated
import at.backend.trading.presentation.TradingBroadcaster
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant

class TradingBroadcastListenerTest : FunSpec({

    val broadcaster = mockk<TradingBroadcaster>(relaxed = true)
    val listener = TradingBroadcastListener(broadcaster)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("TradingCycleCreated → broadcaster.cycleCreated") {
        listener.onCycleCreated(TradingCycleCreated(11L, "005930", "삼성전자", ts))
        verify { broadcaster.cycleCreated(11L, "005930", "삼성전자", ts) }
    }

    test("TradingCycleClosed → broadcaster.cycleClosed") {
        listener.onCycleClosed(TradingCycleClosed(22L, "STOP_LOSS", ts))
        verify { broadcaster.cycleClosed(22L, "STOP_LOSS", ts) }
    }

    test("PriceUpdated → broadcaster.priceUpdated") {
        listener.onPriceUpdated(PriceUpdated(33L, 70_500, BigDecimal("0.500"), 10_000L, ts))
        verify { broadcaster.priceUpdated(33L, 70_500, BigDecimal("0.500"), 10_000L, ts) }
    }

    test("CycleStateChanged → broadcaster.stateChanged") {
        listener.onCycleStateChanged(CycleStateChanged(44L, "HOLDING", null, ts))
        verify { broadcaster.stateChanged(44L, "HOLDING", null, ts) }
    }

    test("SignalArmed → broadcaster.signalArmed") {
        listener.onSignalArmed(SignalArmed(55L, "Breakeven", ts))
        verify { broadcaster.signalArmed(55L, "Breakeven", ts) }
    }

    test("SignalFired → broadcaster.signalFired") {
        listener.onSignalFired(SignalFired(66L, "TpStage", 5, ts))
        verify { broadcaster.signalFired(66L, "TpStage", 5, ts) }
    }

    test("OrderExecuted → broadcaster.orderExecuted") {
        listener.onOrderExecuted(OrderExecuted(77L, "BUY", 14, 70_000, 14, 14, 70_000, ts))
        verify { broadcaster.orderExecuted(77L, "BUY", 14, 70_000, 14, 14, 70_000, ts) }
    }

    test("RetryAccumulated → broadcaster.retryAccumulated") {
        listener.onRetryAccumulated(RetryAccumulated(88L, "Breakeven", 2, "timeout", ts))
        verify { broadcaster.retryAccumulated(88L, "Breakeven", 2, "timeout", ts) }
    }
})
