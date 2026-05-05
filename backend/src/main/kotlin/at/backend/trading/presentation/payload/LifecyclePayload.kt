package at.backend.trading.presentation.payload

import java.time.Instant

/**
 * `/topic/trading/lifecycle` 페이로드 (spec §11.5).
 */
sealed interface LifecyclePayload {
    val type: String
    val ts: Instant

    data class Created(
        val commandId: Long,
        val stockCode: String,
        val stockName: String,
        override val ts: Instant,
    ) : LifecyclePayload {
        override val type: String = "CREATED"
    }

    data class Closed(
        val commandId: Long,
        val closeReason: String,
        override val ts: Instant,
    ) : LifecyclePayload {
        override val type: String = "CLOSED"
    }
}
