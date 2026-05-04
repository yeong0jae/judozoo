package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.market.domain.Bar
import at.backend.platform.kis.client.KisRestClient
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

@Component
class OrderExecutor(
    private val orderRepository: OrderJpaRepository,
    private val kisRestClient: KisRestClient,
    private val timeProvider: TimeProvider,
    @Value("\${trading.order.sell-retry-delay-millis:5000}") private val sellRetryDelayMillis: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 매수 회차 1건 발송. 응답 정상이면 [Order.kisOrderNo]/[Order.krxFwdgOrdOrgno] 갱신.
     * 발송 실패는 회차 스킵 — 호출자(BUYING 사이클)는 다음 회차로 진행.
     */
    suspend fun executeBuyTry(cycle: TradingCycle, attempt: Int): BuyOutcome {
        val currentPrice = kisRestClient.getCurrentPrice(cycle.stockCode).output.stckPrpr.toIntOrNull()
            ?: return BuyOutcome.Skipped("현재가 응답 파싱 실패")
        val qty = (cycle.perBuyAmount / currentPrice).toInt()
        if (qty <= 0) return BuyOutcome.Skipped("perBuyAmount(${cycle.perBuyAmount})가 1주 가격($currentPrice)보다 작음")

        val order = orderRepository.save(
            Order(
                cycleId = cycle.id,
                side = "BUY",
                trigger = "BUY_$attempt",
                orderQty = qty,
                status = "PENDING",
            )
        )

        return try {
            val response = kisRestClient.submitOrder(cycle.stockCode, "BUY", qty)
            order.acknowledge(response.output.odno, response.output.krxFwdgOrdOrgno)
            BuyOutcome.Submitted(orderRepository.save(order))
        } catch (e: Exception) {
            order.markFailed(e.message)
            orderRepository.save(order)
            log.warn("매수 발송 실패 cycleId={}, attempt={}", cycle.id, attempt, e)
            BuyOutcome.Skipped(e.message ?: "발송 실패")
        }
    }

    /**
     * 매도 시그널 처리. 시그널이 살아있는 동안 재시도 루프.
     * - B-3 충돌 방지: in-flight 매도 미체결 수량을 차감해 effectiveQty 산출
     * - 발송 성공 시 종료 (체결 확정은 WS 통보 핸들러 책임)
     * - 발송 실패 시 [sellRetryDelayMillis] 후 재시도
     */
    suspend fun executeSell(
        cycle: TradingCycle,
        signal: Signal,
        intentQty: Int,
        buyPrice: Int,
        currentPrice: Int,
        currentBar: Bar?,
    ): SellOutcome {
        while (true) {
            val nowInstant = timeProvider.now().atZone(KST).toInstant()
            if (!signal.isAlive(currentPrice, buyPrice, currentBar, nowInstant)) {
                cancelInFlightSells(cycle.id)
                return SellOutcome.SignalDead
            }

            val inFlight = orderRepository.inFlightSellUnfilled(cycle.id)
            val effectiveQty = (intentQty - inFlight).coerceAtLeast(0)
            if (effectiveQty == 0) return SellOutcome.NoQty

            val order = orderRepository.save(
                Order(
                    cycleId = cycle.id,
                    side = "SELL",
                    trigger = signal.triggerLabel(),
                    orderQty = effectiveQty,
                    status = "PENDING",
                )
            )

            try {
                val response = kisRestClient.submitOrder(cycle.stockCode, "SELL", effectiveQty)
                order.acknowledge(response.output.odno, response.output.krxFwdgOrdOrgno)
                return SellOutcome.Submitted(orderRepository.save(order))
            } catch (e: Exception) {
                order.markRetryableFailed(e.message)
                orderRepository.save(order)
                log.warn(
                    "매도 발송 실패 cycleId={}, signal={}, retry={}",
                    cycle.id, signal::class.simpleName, order.retryCount, e,
                )
                delay(sellRetryDelayMillis.milliseconds)
            }
        }
    }

    private fun cancelInFlightSells(cycleId: Long) {
        val orders = orderRepository.findInFlightSells(cycleId)
        for (order in orders) {
            val orgno = order.krxFwdgOrdOrgno
            val odno = order.kisOrderNo
            if (orgno != null && odno != null) {
                runCatching { kisRestClient.cancelRemainder(orgno, odno) }
                    .onFailure { log.warn("매도 잔량 취소 실패 cycleId={}, orderId={}", cycleId, order.id, it) }
            }
            order.markCancelled()
            orderRepository.save(order)
        }
    }

    private fun Signal.triggerLabel(): String = when (this) {
        is Signal.TpStage -> "TP_STAGE_$pct"
        else -> this::class.simpleName ?: "SELL"
    }

    sealed class BuyOutcome {
        data class Submitted(val order: Order) : BuyOutcome()
        data class Skipped(val reason: String) : BuyOutcome()
    }

    sealed class SellOutcome {
        data class Submitted(val order: Order) : SellOutcome()
        data object SignalDead : SellOutcome()
        data object NoQty : SellOutcome()
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
