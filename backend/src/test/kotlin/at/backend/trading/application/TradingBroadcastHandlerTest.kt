package at.backend.trading.application

import at.backend.trading.domain.event.TradingCycleClosed
import at.backend.trading.domain.event.TradingCycleCreated
import at.backend.trading.presentation.payload.LifecyclePayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant

class TradingBroadcastHandlerTest : FunSpec({

    val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    val handler = TradingBroadcastHandler(messagingTemplate)

    test("TradingCycleCreated 이벤트는 lifecycle 토픽 CREATED 페이로드로 변환된다") {
        val ts = Instant.parse("2026-01-02T01:00:00Z")
        handler.onCycleCreated(
            TradingCycleCreated(commandId = 11L, stockCode = "005930", stockName = "삼성전자", ts = ts)
        )

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/lifecycle",
                LifecyclePayload.Created(commandId = 11L, stockCode = "005930", stockName = "삼성전자", ts = ts),
            )
        }
    }

    test("TradingCycleClosed 이벤트는 lifecycle 토픽 CLOSED 페이로드로 변환된다") {
        val ts = Instant.parse("2026-01-02T05:30:00Z")
        handler.onCycleClosed(
            TradingCycleClosed(commandId = 22L, closeReason = "STOP_LOSS", ts = ts)
        )

        verify {
            messagingTemplate.convertAndSend(
                "/topic/trading/lifecycle",
                LifecyclePayload.Closed(commandId = 22L, closeReason = "STOP_LOSS", ts = ts),
            )
        }
    }
})
