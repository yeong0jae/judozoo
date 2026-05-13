package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.library.time.toInstantKst
import at.backend.market.domain.Bar
import at.backend.platform.kis.client.KisOrderRejectedException
import at.backend.platform.kis.client.KisRestClient
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
import kotlin.time.Duration.Companion.milliseconds

@Component
class OrderService(
    private val orderRepository: OrderJpaRepository,
    private val kisRestClient: KisRestClient,
    private val timeProvider: TimeProvider,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${trading.order.sell-retry-delay-millis}") private val sellRetryDelayMillis: Long,
) {

    private val log = KotlinLogging.logger {}

    /**
     * 매수 회차 1건 발송. 응답 정상이면 [Order.kisOrderNo]/[Order.krxFwdgOrdOrgno] 갱신.
     * 발송 실패는 회차 스킵 — 호출자(BUYING 사이클)는 다음 회차로 진행.
     */
    suspend fun placeOrder(cycle: TradingCycle, attempt: Int) {
        val currentPrice = try {
            kisRestClient.getCurrentPrice(cycle.stockCode).output.stckPrpr.toIntOrNull()
        } catch (e: Exception) {
            // KIS 일시 거부(EGW00201 등) 시 회차 스킵 — 사이클 전체가 죽지 않도록.
            // PRD 매수 §7: 회차별 발송 실패는 스킵, 다음 회차는 예정 시각에 정상 시도.
            log.warn(e) { "매수 스킵 cycleId=${cycle.id}, attempt=$attempt — 현재가 조회 실패" }
            return
        }
        if (currentPrice == null) {
            log.warn { "매수 스킵 cycleId=${cycle.id}, attempt=$attempt — 현재가 응답 파싱 실패" }
            return
        }
        val qty = (cycle.perBuyAmount / currentPrice).toInt()
        if (qty <= 0) {
            log.warn { "매수 스킵 cycleId=${cycle.id}, attempt=$attempt — perBuyAmount(${cycle.perBuyAmount})가 1주 가격($currentPrice)보다 작음" }
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
            val output = kisRestClient.requestOrder(cycle.stockCode, OrderSide.BUY.name, qty).output!!
            order.acknowledge(output.odno, output.krxFwdgOrdOrgno)
            orderRepository.save(order)
        } catch (e: Exception) {
            order.markFailed(e.message)
            orderRepository.save(order)
            val msgCd = (e as? KisOrderRejectedException)?.msgCd
            log.warn(e) { "매수 발송 실패 cycleId=${cycle.id}, attempt=$attempt, msgCd=$msgCd" }
        }
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
                cancelInFlightSells(cycle.id)
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
                val output =
                    kisRestClient.requestOrder(cycle.stockCode, OrderSide.SELL.name, effectiveQty).output!!
                order.acknowledge(output.odno, output.krxFwdgOrdOrgno)
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

    fun cancelInFlightBuys(cycleId: Long) {
        cancelInFlight(orderRepository.findInFlightBuys(cycleId), cycleId, "매수")
    }

    private fun cancelInFlightSells(cycleId: Long) {
        cancelInFlight(orderRepository.findInFlightSells(cycleId), cycleId, "매도")
    }

    private fun cancelInFlight(orders: List<Order>, cycleId: Long, label: String) {
        for (order in orders) {
            val orgno = order.fwdgOrdOrgno
            val odno = order.orderNo
            if (orgno != null && odno != null) {
                runCatching { kisRestClient.cancelRemainder(orgno, odno) }
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
}
