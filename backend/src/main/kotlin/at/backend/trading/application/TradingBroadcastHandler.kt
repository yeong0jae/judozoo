package at.backend.trading.application

import at.backend.trading.domain.event.TradingCycleClosed
import at.backend.trading.domain.event.TradingCycleCreated
import at.backend.trading.presentation.payload.LifecyclePayload
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Trading 도메인 이벤트를 STOMP 토픽으로 변환.
 * 사이클 흐름과 broadcast 결합도를 분리하기 위해 listener pattern으로 구성.
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

    companion object {
        private const val LIFECYCLE_TOPIC = "/topic/trading/lifecycle"
    }
}
