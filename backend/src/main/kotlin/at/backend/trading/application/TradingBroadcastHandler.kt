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
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Trading 도메인 이벤트를 STOMP 토픽으로 변환.
 * - lifecycle 이벤트 → `/topic/trading/lifecycle`
 * - 사이클별 흐름 이벤트 → `/topic/trading/{commandId}`
 */
@Component
class TradingBroadcastHandler(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @EventListener
    fun onCycleCreated(event: TradingCycleCreated) {
        messagingTemplate.convertAndSend(
            LIFECYCLE_TOPIC,
            LifecyclePayload.Created(
                commandId = event.commandId,
                stockCode = event.stockCode,
                stockName = event.stockName,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onCycleClosed(event: TradingCycleClosed) {
        messagingTemplate.convertAndSend(
            LIFECYCLE_TOPIC,
            LifecyclePayload.Closed(
                commandId = event.commandId,
                closeReason = event.closeReason,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onPriceUpdated(event: PriceUpdated) {
        messagingTemplate.convertAndSend(
            cycleTopic(event.commandId),
            TradingPayload.Price(
                currentPrice = event.currentPrice,
                profitRate = event.profitRate,
                profitAmount = event.profitAmount,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onCycleStateChanged(event: CycleStateChanged) {
        messagingTemplate.convertAndSend(
            cycleTopic(event.commandId),
            TradingPayload.State(
                status = event.status,
                closeReason = event.closeReason,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onSignalArmed(event: SignalArmed) {
        messagingTemplate.convertAndSend(
            cycleTopic(event.commandId),
            TradingPayload.Signal(
                signalType = event.signalType,
                event = TradingPayload.Signal.EVENT_ARMED,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onSignalFired(event: SignalFired) {
        messagingTemplate.convertAndSend(
            cycleTopic(event.commandId),
            TradingPayload.Signal(
                signalType = event.signalType,
                event = TradingPayload.Signal.EVENT_FIRED,
                stage = event.stage,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onOrderExecuted(event: OrderExecuted) {
        messagingTemplate.convertAndSend(
            cycleTopic(event.commandId),
            TradingPayload.Execution(
                side = event.side,
                qty = event.qty,
                price = event.price,
                totalFilledQty = event.totalFilledQty,
                holdingQty = event.holdingQty,
                averageBuyPrice = event.averageBuyPrice,
                ts = event.ts,
            ),
        )
    }

    @EventListener
    fun onRetryAccumulated(event: RetryAccumulated) {
        messagingTemplate.convertAndSend(
            cycleTopic(event.commandId),
            TradingPayload.Retry(
                signalType = event.signalType,
                retryCount = event.retryCount,
                lastError = event.lastError,
                ts = event.ts,
            ),
        )
    }

    private fun cycleTopic(commandId: Long) = "/topic/trading/$commandId"

    companion object {
        private const val LIFECYCLE_TOPIC = "/topic/trading/lifecycle"
    }
}
