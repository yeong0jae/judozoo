package at.backend.account.application

import at.backend.account.domain.event.BalanceInvalidated
import at.backend.account.presentation.AccountBroadcaster
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
