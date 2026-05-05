package at.backend.trading.presentation.payload

import java.math.BigDecimal
import java.time.Instant

/**
 * `/topic/trading/{id}` 페이로드 (spec §11.5).
 * 단방향 (서버 → 클라이언트) 직렬화 전용.
 */
sealed interface TradingPayload {
    val type: String
    val ts: Instant

    data class Price(
        val currentPrice: Int,
        val profitRate: BigDecimal,
        val profitAmount: Long,
        override val ts: Instant,
    ) : TradingPayload {
        override val type: String = "PRICE"
    }

    data class State(
        val status: String,
        val closeReason: String? = null,
        override val ts: Instant,
    ) : TradingPayload {
        override val type: String = "STATE"
    }

    data class Signal(
        val signalType: String,
        val event: String,
        val stage: Int? = null,
        override val ts: Instant,
    ) : TradingPayload {
        override val type: String = "SIGNAL"

        companion object {
            const val EVENT_ARMED = "ARMED"
            const val EVENT_FIRED = "FIRED"
        }
    }

    data class Execution(
        val side: String,
        val qty: Int,
        val price: Int,
        val totalFilledQty: Int,
        val holdingQty: Int,
        val averageBuyPrice: Int,
        override val ts: Instant,
    ) : TradingPayload {
        override val type: String = "EXECUTION"
    }

    data class Retry(
        val signalType: String,
        val retryCount: Int,
        val lastError: String?,
        override val ts: Instant,
    ) : TradingPayload {
        override val type: String = "RETRY"
    }
}
