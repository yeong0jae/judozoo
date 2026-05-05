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
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Trading 도메인 이벤트를 [TradingBroadcaster]에 위임.
 * - listener는 application 책임 (이벤트 라우팅)
 * - 페이로드 변환 / 토픽 path는 broadcaster (presentation 책임)
 */
@Component
class TradingBroadcastListener(
    private val broadcaster: TradingBroadcaster,
) {

    @EventListener
    fun onCycleCreated(event: TradingCycleCreated) =
        broadcaster.cycleCreated(event.commandId, event.stockCode, event.stockName, event.ts)

    @EventListener
    fun onCycleClosed(event: TradingCycleClosed) =
        broadcaster.cycleClosed(event.commandId, event.closeReason, event.ts)

    @EventListener
    fun onPriceUpdated(event: PriceUpdated) =
        broadcaster.priceUpdated(event.commandId, event.currentPrice, event.profitRate, event.profitAmount, event.ts)

    @EventListener
    fun onCycleStateChanged(event: CycleStateChanged) =
        broadcaster.stateChanged(event.commandId, event.status, event.closeReason, event.ts)

    @EventListener
    fun onSignalArmed(event: SignalArmed) =
        broadcaster.signalArmed(event.commandId, event.signalType, event.ts)

    @EventListener
    fun onSignalFired(event: SignalFired) =
        broadcaster.signalFired(event.commandId, event.signalType, event.stage, event.ts)

    @EventListener
    fun onOrderExecuted(event: OrderExecuted) =
        broadcaster.orderExecuted(
            event.commandId, event.side, event.qty, event.price,
            event.totalFilledQty, event.holdingQty, event.averageBuyPrice, event.ts,
        )

    @EventListener
    fun onRetryAccumulated(event: RetryAccumulated) =
        broadcaster.retryAccumulated(event.commandId, event.signalType, event.retryCount, event.lastError, event.ts)
}
