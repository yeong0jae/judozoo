package at.backend.account.application

import at.backend.account.domain.event.BalanceInvalidated
import at.backend.account.presentation.payload.BalanceInvalidatedPayload
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Account 도메인 이벤트를 STOMP 토픽(`/topic/account`)으로 변환.
 */
@Component
class AccountBroadcastHandler(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @EventListener
    fun onBalanceInvalidated(event: BalanceInvalidated) {
        messagingTemplate.convertAndSend(
            ACCOUNT_TOPIC,
            BalanceInvalidatedPayload(ts = event.ts),
        )
    }

    companion object {
        private const val ACCOUNT_TOPIC = "/topic/account"
    }
}
