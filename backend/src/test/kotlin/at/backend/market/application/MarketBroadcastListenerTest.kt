package at.backend.market.application

import at.backend.market.domain.event.HolidayChanged
import at.backend.market.domain.event.MarketModeChanged
import at.backend.market.presentation.MarketBroadcaster
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class MarketBroadcastListenerTest : FunSpec({

    val broadcaster = mockk<MarketBroadcaster>(relaxed = true)
    val listener = MarketBroadcastListener(broadcaster)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("MarketModeChanged → broadcaster.marketModeChanged") {
        listener.onMarketModeChanged(MarketModeChanged("POLLING", ts))
        verify { broadcaster.marketModeChanged("POLLING", ts) }
    }

    test("HolidayChanged → broadcaster.holidayChanged") {
        listener.onHolidayChanged(HolidayChanged(true, ts))
        verify { broadcaster.holidayChanged(true, ts) }
    }
})
