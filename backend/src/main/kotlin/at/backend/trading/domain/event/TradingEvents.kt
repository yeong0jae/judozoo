package at.backend.trading.domain.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Trading 사이클 흐름의 도메인 이벤트.
 * Spring `ApplicationEventPublisher`로 발행, broadcast handler가 listen해 STOMP 토픽으로 변환.
 */

data class TradingCycleCreated(
    val commandId: Long,
    val stockCode: String,
    val stockName: String,
    val ts: Instant,
)

data class TradingCycleClosed(
    val commandId: Long,
    val closeReason: String,
    val ts: Instant,
)

data class CycleStateChanged(
    val commandId: Long,
    val status: String,
    val closeReason: String? = null,
    val ts: Instant,
)

data class PriceUpdated(
    val commandId: Long,
    val currentPrice: Int,
    val profitRate: BigDecimal,
    val profitAmount: Long,
    val ts: Instant,
)

data class SignalArmed(
    val commandId: Long,
    val signalType: String,
    val ts: Instant,
)

data class SignalFired(
    val commandId: Long,
    val signalType: String,
    val stage: Int? = null,
    val ts: Instant,
)

data class OrderExecuted(
    val commandId: Long,
    val side: String,
    val qty: Int,
    val price: Int,
    val totalFilledQty: Int,
    val holdingQty: Int,
    val averageBuyPrice: Int,
    val ts: Instant,
)

data class RetryAccumulated(
    val commandId: Long,
    val signalType: String,
    val retryCount: Int,
    val lastError: String?,
    val ts: Instant,
)
