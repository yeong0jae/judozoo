package at.backend.market.presentation

import at.backend.market.presentation.payload.HolidayPayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant

class MarketBroadcasterTest : FunSpec({

    val template = mockk<SimpMessagingTemplate>(relaxed = true)
    val broadcaster = MarketBroadcaster(template)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("holidayChanged는 market 토픽 HOLIDAY 페이로드를 발행한다") {
        broadcaster.holidayChanged(true, ts)
        verify { template.convertAndSend("/topic/market", HolidayPayload(true, ts)) }
    }
})
