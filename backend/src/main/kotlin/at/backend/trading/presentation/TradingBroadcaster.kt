package at.backend.trading.presentation

import at.backend.trading.presentation.payload.LifecyclePayload
import at.backend.trading.presentation.payload.TradingPayload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * Trading 도메인의 STOMP 토픽 발행 책임.
 * 페이로드 변환 + 토픽 path 관리는 presentation 영역.
 */
@Component
class TradingBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    fun cycleCreated(cycleId: Long, stockCode: String, stockName: String, ts: Instant) {
        messagingTemplate.convertAndSend(
            LIFECYCLE_TOPIC,
            LifecyclePayload.Created(cycleId, stockCode, stockName, ts),
        )
    }

    fun cycleClosed(cycleId: Long, closeReason: String, ts: Instant) {
        messagingTemplate.convertAndSend(
            LIFECYCLE_TOPIC,
            LifecyclePayload.Closed(cycleId, closeReason, ts),
        )
    }

    fun priceUpdated(
        cycleId: Long, currentPrice: Int, profitRate: BigDecimal, profitAmount: Long, ts: Instant,
    ) {
        messagingTemplate.convertAndSend(
            cycleTopic(cycleId),
            TradingPayload.Price(currentPrice, profitRate, profitAmount, ts),
        )
    }

    fun stateChanged(cycleId: Long, status: String, closeReason: String?, ts: Instant) {
        messagingTemplate.convertAndSend(
            cycleTopic(cycleId),
            TradingPayload.State(status, closeReason, ts),
        )
    }

    fun signalArmed(cycleId: Long, signalType: String, ts: Instant) {
        messagingTemplate.convertAndSend(
            cycleTopic(cycleId),
            TradingPayload.Signal(signalType, TradingPayload.Signal.EVENT_ARMED, null, ts),
        )
    }

    fun signalFired(cycleId: Long, signalType: String, stage: Int?, ts: Instant) {
        messagingTemplate.convertAndSend(
            cycleTopic(cycleId),
            TradingPayload.Signal(signalType, TradingPayload.Signal.EVENT_FIRED, stage, ts),
        )
    }

    fun orderExecuted(
        cycleId: Long, side: String, qty: Int, price: Int,
        totalFilledQty: Int, holdingQty: Int, averageBuyPrice: Int, ts: Instant,
    ) {
        messagingTemplate.convertAndSend(
            cycleTopic(cycleId),
            TradingPayload.Execution(side, qty, price, totalFilledQty, holdingQty, averageBuyPrice, ts),
        )
    }

    fun retryAccumulated(cycleId: Long, signalType: String, retryCount: Int, lastError: String?, ts: Instant) {
        messagingTemplate.convertAndSend(
            cycleTopic(cycleId),
            TradingPayload.Retry(signalType, retryCount, lastError, ts),
        )
    }

    private fun cycleTopic(cycleId: Long) = "/topic/trading/$cycleId"

    companion object {
        private const val LIFECYCLE_TOPIC = "/topic/trading/lifecycle"
    }
}
