package at.backend.account.presentation

import at.backend.account.domain.event.BalanceInvalidated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AccountBroadcastListener(
    private val broadcaster: AccountBroadcaster,
) {

    @EventListener
    fun onBalanceInvalidated(event: BalanceInvalidated) =
        broadcaster.balanceInvalidated(event.ts)
}
