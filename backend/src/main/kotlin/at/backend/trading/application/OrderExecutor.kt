package at.backend.trading.application

import at.backend.library.time.TimeProvider
import at.backend.market.domain.Bar
import at.backend.platform.kis.client.KisOrderRejectedException
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisDailyCcldResponse
import at.backend.trading.domain.cycle.TradingCycle
import at.backend.trading.domain.event.RetryAccumulated
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.order.Order
import at.backend.trading.domain.signal.Signal
import at.backend.trading.infrastructure.repository.ExecutionJpaRepository
import at.backend.trading.infrastructure.repository.OrderJpaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Component
class OrderExecutor(
    private val orderRepository: OrderJpaRepository,
    private val executionRepository: ExecutionJpaRepository,
    private val kisRestClient: KisRestClient,
    private val timeProvider: TimeProvider,
    private val applicationScope: CoroutineScope,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${trading.order.sell-retry-delay-millis:5000}") private val sellRetryDelayMillis: Long,
    @Value("\${trading.order.reconcile-delay-millis:5000}") private val reconcileDelayMillis: Long,
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
            val output = kisRestClient.submitOrder(cycle.stockCode, "BUY", qty).output!!
            order.acknowledge(output.odno, output.krxFwdgOrdOrgno)
            val saved = orderRepository.save(order)
            scheduleReconcile(saved, cycle.stockCode)
            BuyOutcome.Submitted(saved)
        } catch (e: KisOrderRejectedException) {
            // KIS가 명시 거부 (rt_cd != 0) — 실 체결 없음 확정.
            order.markFailed(e.message)
            orderRepository.save(order)
            log.warn("매수 거부 cycleId={}, attempt={}, msgCd={}", cycle.id, attempt, e.msgCd, e)
            BuyOutcome.Skipped(e.message ?: "발송 거부")
        } catch (e: Exception) {
            // 응답 파싱 실패 / HTTP 오류 / 타임아웃 — KIS가 받았는지 모름.
            // PENDING 유지 + reconcile로 일별 체결 조회해 실 체결 여부 확인 (DB와 KIS 보유 분리 방지).
            order.markUncertain(e.message)
            val saved = orderRepository.save(order)
            scheduleReconcile(saved, cycle.stockCode)
            log.warn("매수 응답 불확실 cycleId={}, attempt={} — reconcile로 실 체결 확인", cycle.id, attempt, e)
            BuyOutcome.Submitted(saved)
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
                val output = kisRestClient.submitOrder(cycle.stockCode, "SELL", effectiveQty).output!!
                order.acknowledge(output.odno, output.krxFwdgOrdOrgno)
                val saved = orderRepository.save(order)
                scheduleReconcile(saved, cycle.stockCode)
                return SellOutcome.Submitted(saved)
            } catch (e: Exception) {
                // SELL은 응답 거부/응답 불확실 모두 retry 루프로 처리. 단 응답 불확실(KisOrderRejected가 아닌 경우)은
                // KIS가 받았을 가능성 있어 reconcile도 함께 예약 — 다음 루프에서 inFlightSellUnfilled가 자동 반영.
                order.markRetryableFailed(e.message)
                val saved = orderRepository.save(order)
                if (e !is KisOrderRejectedException) {
                    scheduleReconcile(saved, cycle.stockCode)
                }
                eventPublisher.publishEvent(
                    RetryAccumulated(
                        commandId = cycle.id,
                        signalType = signal::class.simpleName ?: "Signal",
                        retryCount = order.retryCount,
                        lastError = e.message,
                        ts = timeProvider.now().atZone(KST).toInstant(),
                    )
                )
                log.warn("매도 발송 실패 cycleId={}, signal={}, retry={}",
                    cycle.id, signal::class.simpleName, order.retryCount, e)
                delay(sellRetryDelayMillis.milliseconds)
            }
        }
    }

    fun cancelInFlightBuys(cycleId: Long) {
        cancelInFlight(orderRepository.findInFlightBuys(cycleId), cycleId, "매수")
    }

    /**
     * WS 체결 통보 누락 / 5초 timeout 시 호출 — KIS 일별 체결 조회로 동기화.
     * - 매칭 우선순위: `kisOrderNo` > 시간 윈도우 ±30초 + 종목 + side + 수량 fallback
     * - 1건 매칭 → Order/Execution 갱신, 0건 → no-op (재발송 안전), 2건+ → manual review
     */
    fun reconcile(orderId: Long, stockCode: String): ReconcileOutcome {
        val order = orderRepository.findById(orderId).orElse(null) ?: return ReconcileOutcome.NotFound
        if (!order.isReconcilable()) return ReconcileOutcome.Skipped
        val today = timeProvider.now().toLocalDate()
        val response = runCatching { kisRestClient.getDailyExecutions(stockCode, today) }
            .getOrElse {
                log.warn("일별 체결 조회 실패 orderId={}", order.id, it)
                return ReconcileOutcome.LookupFailed
            }
        val candidates = matchCandidates(order, stockCode, response.output1)
        return when (candidates.size) {
            0 -> ReconcileOutcome.NoMatch
            1 -> applyReconciledExecution(order, candidates.single())
            else -> {
                order.markNeedsManualReview("일별 체결 ${candidates.size}건 매칭")
                orderRepository.save(order)
                log.warn("reconcile 다중 매칭 orderId={}, count={}", order.id, candidates.size)
                ReconcileOutcome.MultipleMatches
            }
        }
    }

    private fun matchCandidates(
        order: Order,
        stockCode: String,
        outputs: List<KisDailyCcldResponse.Output>,
    ): List<KisDailyCcldResponse.Output> {
        val byKisOrderNo = order.kisOrderNo?.let { kisOrderNo -> outputs.filter { it.odno == kisOrderNo } }
        if (!byKisOrderNo.isNullOrEmpty()) return byKisOrderNo
        return outputs.filter { row -> matchesByFallback(order, stockCode, row) }
    }

    private fun matchesByFallback(order: Order, stockCode: String, row: KisDailyCcldResponse.Output): Boolean {
        if (row.pdno != stockCode) return false
        if (sideOf(row) != order.side) return false
        if (row.totCcldQty.toIntOrNull() != order.orderQty) return false
        val orderedAt = parseOrderTime(row) ?: return false
        return abs(java.time.Duration.between(order.createdAt, orderedAt).seconds) <= FALLBACK_WINDOW_SECONDS
    }

    private fun sideOf(row: KisDailyCcldResponse.Output): String? = when (row.sllBuyDvsnCd) {
        SLL_BUY_BUY -> "BUY"
        SLL_BUY_SELL -> "SELL"
        else -> null
    }

    private fun parseOrderTime(row: KisDailyCcldResponse.Output): LocalDateTime? = runCatching {
        LocalDateTime.parse(row.ordDt + row.ordTmd, ORD_TS_FMT)
    }.getOrNull()

    private fun applyReconciledExecution(order: Order, output: KisDailyCcldResponse.Output): ReconcileOutcome {
        val totalFilled = output.totCcldQty.toIntOrNull() ?: 0
        val avgPrice = output.avgPrvs.toIntOrNull() ?: 0
        if (totalFilled <= 0 || avgPrice <= 0) {
            log.warn("reconcile 응답 파싱 실패 orderId={}, output={}", order.id, output)
            return ReconcileOutcome.NoMatch
        }
        order.reconcileFilled(totalFilled)
        orderRepository.save(order)
        executionRepository.save(
            Execution(
                orderId = order.id,
                executedQty = totalFilled,
                executedPrice = avgPrice,
                fee = 0,
                tax = 0,
            )
        )
        return ReconcileOutcome.Matched(order.id, totalFilled)
    }

    private fun scheduleReconcile(order: Order, stockCode: String) {
        val orderId = order.id
        applicationScope.launch {
            delay(reconcileDelayMillis.milliseconds)
            runCatching { reconcile(orderId, stockCode) }
                .onFailure { log.warn("reconcile 실행 실패 orderId={}", orderId, it) }
        }
    }

    private fun cancelInFlightSells(cycleId: Long) {
        cancelInFlight(orderRepository.findInFlightSells(cycleId), cycleId, "매도")
    }

    private fun cancelInFlight(orders: List<Order>, cycleId: Long, label: String) {
        for (order in orders) {
            val orgno = order.krxFwdgOrdOrgno
            val odno = order.kisOrderNo
            if (orgno != null && odno != null) {
                runCatching { kisRestClient.cancelRemainder(orgno, odno) }
                    .onFailure { log.warn("$label 잔량 취소 실패 cycleId={}, orderId={}", cycleId, order.id, it) }
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

    sealed class ReconcileOutcome {
        data class Matched(val orderId: Long, val filledQty: Int) : ReconcileOutcome()
        data object NoMatch : ReconcileOutcome()
        data object MultipleMatches : ReconcileOutcome()
        data object Skipped : ReconcileOutcome()
        data object NotFound : ReconcileOutcome()
        data object LookupFailed : ReconcileOutcome()
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val FALLBACK_WINDOW_SECONDS = 30L
        private const val SLL_BUY_BUY = "02"
        private const val SLL_BUY_SELL = "01"
        private val ORD_TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
