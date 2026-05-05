package at.backend.market.application

import at.backend.market.domain.event.HolidayChanged
import at.backend.market.domain.event.MarketModeChanged
import at.backend.market.presentation.payload.HolidayPayload
import at.backend.market.presentation.payload.MarketModePayload
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Market 도메인 이벤트를 STOMP 토픽(`/topic/market`)으로 변환.
 */
@Component
class MarketBroadcastHandler(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @EventListener
    fun onMarketModeChanged(event: MarketModeChanged) {
        messagingTemplate.convertAndSend(
            MARKET_TOPIC,
            MarketModePayload(mode = event.mode, ts = event.ts),
        )
    }

    @EventListener
    fun onHolidayChanged(event: HolidayChanged) {
        messagingTemplate.convertAndSend(
            MARKET_TOPIC,
            HolidayPayload(isHoliday = event.isHoliday, ts = event.ts),
        )
    }

    companion object {
        private const val MARKET_TOPIC = "/topic/market"
    }
}
