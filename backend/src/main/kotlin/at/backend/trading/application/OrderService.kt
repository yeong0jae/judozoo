package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.market.domain.Bar
import at.backend.trading.application.broker.BrokerOrderRejectedException
import at.backend.trading.application.broker.BrokerTradingClient
import at.backend.trading.application.broker.PlacedOrder
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.event.RetryAccumulated
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.order.OrderSide
import at.backend.trading.domain.order.OrderStatus
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import kotlinx.coroutines.delay
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpServerErrorException
import kotlin.time.Duration.Companion.milliseconds

@Component
class OrderService(
    private val orderRepository: OrderJpaRepository,
    private val broker: BrokerTradingClient,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${trading.order.sell-retry-delay-millis}") private val sellRetryDelayMillis: Long,
    @Value("\${trading.order.egw-retry-base-delay-millis}") private val egwRetryBaseDelayMs: Long,
) {

    private val log = KotlinLogging.logger {}

    /**
     * 매수 회차 1건 발송. 응답 정상이면 [Order.kisOrderNo]/[Order.krxFwdgOrdOrgno] 갱신.
     * 발송 실패는 회차 스킵 — 호출자(BUYING 사이클)는 다음 회차로 진행.
     */
    suspend fun placeOrder(cycle: TradingCycle, attempt: Int) {
        val currentPrice = try {
            broker.currentPrice(cycle.stockCode).toInt()
        } catch (e: Exception) {
            // 브로커 일시 거부(EGW00201 등) 시 회차 스킵 — 사이클 전체가 죽지 않도록.
            // PRD 매수 §7: 회차별 발송 실패는 스킵, 다음 회차는 예정 시각에 정상 시도.
            log.warn(e) { "매수 스킵 cycleId=${cycle.id}, attempt=$attempt — 현재가 조회 실패" }
            return
        }
        // 신규 사이클은 perBuyQty가 결정값. 과거 사이클은 perBuyAmount/currentPrice로 fallback.
        val qty = cycle.perBuyQty ?: (cycle.perBuyAmount / currentPrice).toInt()
        if (qty <= 0) {
            log.warn { "매수 스킵 cycleId=${cycle.id}, attempt=$attempt — qty=$qty (perBuyQty=${cycle.perBuyQty}, perBuyAmount=${cycle.perBuyAmount}, currentPrice=$currentPrice)" }
            return
        }

        val order = orderRepository.save(
            Order(
                cycleId = cycle.id,
                side = OrderSide.BUY,
                trigger = "BUY_$attempt",
                orderQty = qty,
                status = OrderStatus.PENDING,
            )
        )

        try {
            val placed = submitBuyWithEgw00201Retry(cycle.stockCode, qty, cycle.id, attempt)
            order.acknowledge(placed.orderNo, placed.orgno)
            orderRepository.save(order)
        } catch (e: Exception) {
            order.markFailed(e.message)
            orderRepository.save(order)
            val code = (e as? BrokerOrderRejectedException)?.code
            log.warn(e) { "매수 발송 실패 cycleId=${cycle.id}, attempt=$attempt, code=$code" }
        }
    }

    /**
     * 매수 발송이 EGW00201(초당 거래건수 초과)로 거부되면 짧은 backoff 후 [EGW_RETRIES]회 재시도.
     * KIS가 처리 자체를 안 한 거부라 중복 주문 위험은 없다. 회차 단위 스킵(3분 대기) 대신
     * 동일 회차 내에서 빠르게 재발사해 매수량 손실을 막는다. 다른 거부(잔고 부족·중복 등)는 즉시 전파.
     */
    private suspend fun submitBuyWithEgw00201Retry(
        stockCode: String,
        qty: Int,
        cycleId: Long,
        attempt: Int,
    ): PlacedOrder {
        repeat(EGW_RETRIES) { i ->
            try {
                return broker.placeOrder(stockCode, OrderSide.BUY, qty)
            } catch (e: Exception) {
                if (!isEgw00201(e)) throw e
                val delayMs = (i + 1) * egwRetryBaseDelayMs
                log.info { "매수 발송 EGW00201 → ${delayMs}ms 후 재시도 cycleId=$cycleId, attempt=$attempt (${i + 1}/$EGW_RETRIES)" }
                delay(delayMs.milliseconds)
            }
        }
        return broker.placeOrder(stockCode, OrderSide.BUY, qty)
    }

    private fun isEgw00201(e: Throwable): Boolean = when (e) {
        is BrokerOrderRejectedException -> e.code == EGW_RATE_LIMIT_CODE
        is HttpServerErrorException -> e.responseBodyAsString.contains(EGW_RATE_LIMIT_CODE)
        else -> false
    }

    /**
     * 매도 시그널 처리. 시그널이 살아있는 동안 재시도 루프.
     * - B-3 충돌 방지: in-flight 매도 미체결 수량을 차감해 effectiveQty 산출
     * - 발송 성공 시 종료 (체결 확정은 WS 통보 핸들러 책임)
     * - 발송 실패 시 [sellRetryDelayMillis] 후 재시도
     */
    suspend fun placeSell(
        cycle: TradingCycle,
        signal: Signal,
        intentQty: Int,
        buyPrice: Int,
        currentPrice: Int,
        currentBar: Bar?,
    ) {
        while (true) {
            val nowInstant = timeProvider.now().toInstantKst()
            if (!signal.isAlive(currentPrice, buyPrice, currentBar, nowInstant)) {
                cancelInFlightSells(cycle.id, cycle.stockCode)
                return
            }

            val inFlight = orderRepository.inFlightSellUnfilled(cycle.id)
            val effectiveQty = (intentQty - inFlight).coerceAtLeast(0)
            if (effectiveQty == 0) return

            val order = orderRepository.save(
                Order(
                    cycleId = cycle.id,
                    side = OrderSide.SELL,
                    trigger = signal.triggerLabel(),
                    orderQty = effectiveQty,
                    status = OrderStatus.PENDING,
                )
            )

            try {
                val placed = broker.placeOrder(cycle.stockCode, OrderSide.SELL, effectiveQty)
                order.acknowledge(placed.orderNo, placed.orgno)
                orderRepository.save(order)
                return
            } catch (e: Exception) {
                order.markRetryableFailed(e.message)
                orderRepository.save(order)
                eventPublisher.publishEvent(
                    RetryAccumulated(
                        cycleId = cycle.id,
                        signalType = signal::class.simpleName ?: "Signal",
                        retryCount = order.retryCount,
                        lastError = e.message,
                        ts = timeProvider.now().toInstantKst(),
                    )
                )
                log.warn(e) { "매도 발송 실패 cycleId=${cycle.id}, signal=${signal::class.simpleName}, retry=${order.retryCount}" }
                delay(sellRetryDelayMillis.milliseconds)
            }
        }
    }

    fun cancelInFlightBuys(cycleId: Long, stockCode: String) {
        cancelInFlight(stockCode, orderRepository.findInFlightBuys(cycleId), cycleId, "매수")
    }

    private fun cancelInFlightSells(cycleId: Long, stockCode: String) {
        cancelInFlight(stockCode, orderRepository.findInFlightSells(cycleId), cycleId, "매도")
    }

    private fun cancelInFlight(stockCode: String, orders: List<Order>, cycleId: Long, label: String) {
        for (order in orders) {
            val orgno = order.fwdgOrdOrgno
            val odno = order.orderNo
            if (orgno != null && odno != null) {
                runCatching { broker.cancelOrder(stockCode, orgno, odno) }
                    .onFailure { log.warn(it) { "$label 잔량 취소 실패 cycleId=$cycleId, orderId=${order.id}" } }
            }
            order.markCancelled()
            orderRepository.save(order)
        }
    }

    private fun Signal.triggerLabel(): String = when (this) {
        is Signal.TpStage -> "TP_STAGE_$pct"
        else -> this::class.simpleName ?: "SELL"
    }

    companion object {
        private const val EGW_RATE_LIMIT_CODE = "EGW00201"
        private const val EGW_RETRIES = 2
    }
}
