package at.backend.account.presentation

import at.backend.account.presentation.payload.BalanceInvalidatedPayload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Account 도메인의 STOMP 토픽(`/topic/account`) 발행 책임.
 */
@Component
class AccountBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    fun balanceInvalidated(ts: Instant) {
        messagingTemplate.convertAndSend(ACCOUNT_TOPIC, BalanceInvalidatedPayload(ts))
    }

    companion object {
        private const val ACCOUNT_TOPIC = "/topic/account"
    }
}
