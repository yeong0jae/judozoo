package at.backend.account.application

import at.backend.account.domain.event.BalanceInvalidated
import at.backend.account.presentation.AccountBroadcaster
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class AccountBroadcastListenerTest : FunSpec({

    val broadcaster = mockk<AccountBroadcaster>(relaxed = true)
    val listener = AccountBroadcastListener(broadcaster)
    val ts = Instant.parse("2026-01-02T01:00:00Z")

    test("BalanceInvalidated → broadcaster.balanceInvalidated") {
        listener.onBalanceInvalidated(BalanceInvalidated(ts))
        verify { broadcaster.balanceInvalidated(ts) }
    }
})
