package at.backend.market.domain.event

import at.backend.market.domain.regime.RegimeSnapshot
import java.time.Instant

/**
 * Market 도메인 이벤트.
 * Spring `ApplicationEventPublisher`로 발행, broadcast handler가 listen해 STOMP 토픽으로 변환.
 */

data class HolidayChanged(
    val isHoliday: Boolean,
    val ts: Instant,
)

data class RegimeUpdated(
    val snapshot: RegimeSnapshot,
)
