package at.backend.market.presentation

import at.backend.market.domain.regime.RegimeSnapshot
import at.backend.market.presentation.payload.HolidayPayload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Market 도메인의 STOMP 토픽 발행 책임. (`/topic/market`, `/topic/regime`)
 */
@Component
class MarketBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    fun holidayChanged(isHoliday: Boolean, ts: Instant) {
        messagingTemplate.convertAndSend(MARKET_TOPIC, HolidayPayload(isHoliday, ts))
    }

    fun regimeUpdated(snapshot: RegimeSnapshot) {
        messagingTemplate.convertAndSend(REGIME_TOPIC, snapshot)
    }

    companion object {
        private const val MARKET_TOPIC = "/topic/market"
        private const val REGIME_TOPIC = "/topic/regime"
    }
}
