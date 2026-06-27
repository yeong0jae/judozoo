package at.backend.market.presentation

import at.backend.market.domain.event.HolidayChanged
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class MarketBroadcastListener(
    private val broadcaster: MarketBroadcaster,
) {

    @EventListener
    fun onHolidayChanged(event: HolidayChanged) =
        broadcaster.holidayChanged(event.isHoliday, event.ts)
}
