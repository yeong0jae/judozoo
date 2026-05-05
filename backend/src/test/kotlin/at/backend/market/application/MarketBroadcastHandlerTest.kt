package at.backend.market.application

import at.backend.market.domain.event.HolidayChanged
import at.backend.market.domain.event.MarketModeChanged
import at.backend.market.presentation.payload.HolidayPayload
import at.backend.market.presentation.payload.MarketModePayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant

class MarketBroadcastHandlerTest : FunSpec({

    val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    val handler = MarketBroadcastHandler(messagingTemplate)

    test("MarketModeChanged 이벤트는 market 토픽 MARKET_MODE 페이로드로 변환된다") {
        val ts = Instant.parse("2026-01-02T01:00:00Z")
        handler.onMarketModeChanged(MarketModeChanged(mode = "POLLING", ts = ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/market",
                MarketModePayload(mode = "POLLING", ts = ts),
            )
        }
    }

    test("HolidayChanged 이벤트는 market 토픽 HOLIDAY 페이로드로 변환된다") {
        val ts = Instant.parse("2026-01-02T08:00:00Z")
        handler.onHolidayChanged(HolidayChanged(isHoliday = true, ts = ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/market",
                HolidayPayload(isHoliday = true, ts = ts),
            )
        }
    }
})
