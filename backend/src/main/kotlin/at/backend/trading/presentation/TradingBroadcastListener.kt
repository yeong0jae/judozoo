package at.backend.trading.presentation

import at.backend.trading.domain.event.CycleStateChanged
import at.backend.trading.domain.event.OrderExecuted
import at.backend.trading.domain.event.PriceUpdated
import at.backend.trading.domain.event.RetryAccumulated
import at.backend.trading.domain.event.SignalArmed
import at.backend.trading.domain.event.SignalFired
import at.backend.trading.domain.event.TradingCycleClosed
import at.backend.trading.domain.event.TradingCycleCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Trading 도메인 이벤트를 STOMP 브로커로 라우팅하는 presentation 어댑터.
 * 이벤트 → 브로드캐스터 호출만 수행하며, 페이로드 변환/토픽 경로는 [TradingBroadcaster]가 담당.
 */
@Component
class TradingBroadcastListener(
    private val broadcaster: TradingBroadcaster,
) {

    @EventListener
    fun onCycleCreated(event: TradingCycleCreated) =
        broadcaster.cycleCreated(event.cycleId, event.stockCode, event.stockName, event.ts)

    @EventListener
    fun onCycleClosed(event: TradingCycleClosed) =
        broadcaster.cycleClosed(event.cycleId, event.closeReason, event.ts)

    @EventListener
    fun onPriceUpdated(event: PriceUpdated) =
        broadcaster.priceUpdated(event.cycleId, event.currentPrice, event.profitRate, event.profitAmount, event.ts)

    @EventListener
    fun onCycleStateChanged(event: CycleStateChanged) =
        broadcaster.stateChanged(event.cycleId, event.status, event.closeReason, event.ts)

    @EventListener
    fun onSignalArmed(event: SignalArmed) =
        broadcaster.signalArmed(event.cycleId, event.signalType, event.ts)

    @EventListener
    fun onSignalFired(event: SignalFired) =
        broadcaster.signalFired(event.cycleId, event.signalType, event.stage, event.ts)

    @EventListener
    fun onOrderExecuted(event: OrderExecuted) =
        broadcaster.orderExecuted(
            event.cycleId, event.side, event.qty, event.price,
            event.totalFilledQty, event.holdingQty, event.averageBuyPrice, event.ts,
        )

    @EventListener
    fun onRetryAccumulated(event: RetryAccumulated) =
        broadcaster.retryAccumulated(event.cycleId, event.signalType, event.retryCount, event.lastError, event.ts)
}
