package at.backend.account.application

import at.backend.account.domain.event.BalanceInvalidated
import at.backend.account.presentation.payload.BalanceInvalidatedPayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant

class AccountBroadcastHandlerTest : FunSpec({

    val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    val handler = AccountBroadcastHandler(messagingTemplate)

    test("BalanceInvalidated 이벤트는 account 토픽 페이로드로 변환된다") {
        val ts = Instant.parse("2026-01-02T01:00:00Z")
        handler.onBalanceInvalidated(BalanceInvalidated(ts = ts))

        verify {
            messagingTemplate.convertAndSend(
                "/topic/account",
                BalanceInvalidatedPayload(ts = ts),
            )
        }
    }
})
