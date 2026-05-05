package at.backend.account.presentation

import at.backend.account.presentation.payload.BalanceInvalidatedPayload
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant

class AccountBroadcasterTest : FunSpec({

    val template = mockk<SimpMessagingTemplate>(relaxed = true)
    val broadcaster = AccountBroadcaster(template)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("balanceInvalidated는 account 토픽 페이로드를 발행한다") {
        broadcaster.balanceInvalidated(ts)
        verify { template.convertAndSend("/topic/account", BalanceInvalidatedPayload(ts)) }
    }
})
