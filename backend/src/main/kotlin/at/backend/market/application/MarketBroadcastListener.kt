package at.backend.market.application

import at.backend.market.domain.event.HolidayChanged
import at.backend.market.domain.event.MarketModeChanged
import at.backend.market.presentation.MarketBroadcaster
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class MarketBroadcastListener(
    private val broadcaster: MarketBroadcaster,
) {

    @EventListener
    fun onMarketModeChanged(event: MarketModeChanged) =
        broadcaster.marketModeChanged(event.mode, event.ts)

    @EventListener
    fun onHolidayChanged(event: HolidayChanged) =
        broadcaster.holidayChanged(event.isHoliday, event.ts)
}
