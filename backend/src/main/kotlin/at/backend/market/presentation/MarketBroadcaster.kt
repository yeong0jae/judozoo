package at.backend.market.presentation

import at.backend.market.presentation.payload.HolidayPayload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Market 도메인의 STOMP 토픽(`/topic/market`) 발행 책임.
 */
@Component
class MarketBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    fun holidayChanged(isHoliday: Boolean, ts: Instant) {
        messagingTemplate.convertAndSend(MARKET_TOPIC, HolidayPayload(isHoliday, ts))
    }

    companion object {
        private const val MARKET_TOPIC = "/topic/market"
    }
}
