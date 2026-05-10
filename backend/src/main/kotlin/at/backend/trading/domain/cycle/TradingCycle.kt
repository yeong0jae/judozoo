package at.backend.trading.domain.cycle

import at.backend.library.jpa.BaseEntity
import at.backend.market.domain.Bar
import at.backend.market.domain.PriceTick
import at.backend.trading.domain.AlreadyClosedException
import at.backend.trading.domain.execution.Execution
import at.backend.trading.domain.signal.Signal
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.math.ceil

@Entity
@Table(name = "trading_cycles")
class TradingCycle(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 10)
    val stockCode: String,

    @Column(nullable = false, length = 50)
    val stockName: String,

    @Column(nullable = false)
    val perBuyAmount: Long,

    @Column(nullable = false)
    val buyIntervalMin: Int,

    @Column(nullable = false, precision = 4, scale = 3)
    val splitSellRatio: BigDecimal,

    @Column(nullable = false, precision = 5, scale = 3)
    val midwayProfitPct: BigDecimal,

    @Column(nullable = false, precision = 5, scale = 3)
    val breakevenThresholdPct: BigDecimal,

    @Column(nullable = false, precision = 5, scale = 3)
    val stopLossPct: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TradingCycleStatus = TradingCycleStatus.INITIATED,

    @Column(nullable = false)
    var buyAttempt: Int = 0,

    @Column(nullable = false)
    var tpStagesFired: Int = 0,

    @Column(nullable = false)
    var breakevenArmed: Boolean = false,

    @Column(nullable = false)
    var trendBreakArmed: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    var closeReason: CloseReason? = null,
    
    @Column
    var closedAt: LocalDateTime? = null,

    ) : BaseEntity() {

    init {
        require(stopLossPct < BigDecimal.ZERO) { "손절 비율은 음수여야 합니다: $stopLossPct" }
        require(midwayProfitPct > BigDecimal.ZERO) { "중도 익절 비율은 양수여야 합니다: $midwayProfitPct" }
        require(tpStagesFired in 0..0b111) { "TP 단계 비트플래그는 0~7이어야 합니다: $tpStagesFired" }
    }

    fun requestCancel() {
        when (status) {
            TradingCycleStatus.INITIATED,
            TradingCycleStatus.BUYING,
            TradingCycleStatus.HOLDING -> status = TradingCycleStatus.LIQUIDATING

            TradingCycleStatus.LIQUIDATING -> Unit
            TradingCycleStatus.CLOSED -> throw AlreadyClosedException(id)
        }
    }

    fun startBuying() {
        require(status == TradingCycleStatus.INITIATED) {
            "Initiated 상태에서만 매수 시작 가능: $status"
        }
        status = TradingCycleStatus.BUYING
        buyAttempt = 1
    }

    fun incrementBuyAttempt() {
        require(status == TradingCycleStatus.BUYING) {
            "Buying 상태에서만 회차 증가 가능: $status"
        }
        require(buyAttempt < MAX_BUY_ATTEMPT) {
            "매수 회차는 ${MAX_BUY_ATTEMPT}회를 초과할 수 없습니다: $buyAttempt"
        }
        buyAttempt += 1
    }

    fun transitionToHolding() {
        require(status == TradingCycleStatus.BUYING) {
            "Buying 상태에서만 Holding으로 전이 가능: $status"
        }
        status = TradingCycleStatus.HOLDING
    }

    fun armBreakeven() {
        require(status == TradingCycleStatus.HOLDING) {
            "Holding 상태에서만 Breakeven 무장 가능: $status"
        }
        breakevenArmed = true
    }

    fun disarmBreakeven() {
        breakevenArmed = false
    }

    fun armTrendBreak() {
        require(status == TradingCycleStatus.HOLDING) {
            "Holding 상태에서만 TrendBreak 무장 가능: $status"
        }
        trendBreakArmed = true
    }

    fun markTpStageFired(stagePct: Int) {
        require(status == TradingCycleStatus.HOLDING) {
            "Holding 상태에서만 TpStage 발동 기록 가능: $status"
        }
        tpStagesFired = tpStagesFired or stagePctToBit(stagePct)
    }

    fun close(reason: CloseReason, at: LocalDateTime) {
        if (status == TradingCycleStatus.CLOSED) throw AlreadyClosedException(id)
        require(canCloseWith(reason)) {
            "현재 상태($status)에서는 $reason 사유로 종료할 수 없습니다"
        }
        status = TradingCycleStatus.CLOSED
        closeReason = reason
        closedAt = at
    }

    private fun canCloseWith(reason: CloseReason): Boolean = when (reason) {
        CloseReason.UNCLOSED -> true
        CloseReason.NO_FILL -> status == TradingCycleStatus.BUYING
        CloseReason.CANCELLED -> status == TradingCycleStatus.BUYING || status == TradingCycleStatus.LIQUIDATING
        CloseReason.TAKE_PROFIT,
        CloseReason.STOP_LOSS,
        CloseReason.BREAKEVEN,
        CloseReason.TREND_BREAK,
        CloseReason.MARKET_CLOSE -> status == TradingCycleStatus.LIQUIDATING
    }

    fun canTransitionTo(
        nextStatus: TradingCycleStatus,
        nextBuyAttempt: Int? = null,
        nextCloseReason: CloseReason? = null,
    ): Boolean = when (status) {
        TradingCycleStatus.INITIATED ->
            nextStatus == TradingCycleStatus.BUYING && nextBuyAttempt == 1

        TradingCycleStatus.BUYING -> when (nextStatus) {
            TradingCycleStatus.BUYING ->
                nextBuyAttempt != null && nextBuyAttempt == buyAttempt + 1 && nextBuyAttempt <= 3

            TradingCycleStatus.HOLDING -> true
            TradingCycleStatus.LIQUIDATING -> true
            TradingCycleStatus.CLOSED ->
                nextCloseReason == CloseReason.NO_FILL || nextCloseReason == CloseReason.CANCELLED

            else -> false
        }

        TradingCycleStatus.HOLDING ->
            nextStatus == TradingCycleStatus.HOLDING || nextStatus == TradingCycleStatus.LIQUIDATING

        TradingCycleStatus.LIQUIDATING ->
            nextStatus == TradingCycleStatus.CLOSED

        TradingCycleStatus.CLOSED -> false
    }

    fun detectSignals(tick: PriceTick, currentBar: Bar, prevBar: Bar, holdingQty: Int, buyPrice: Int): List<Signal> {
        val signals = detectSignals(tick, holdingQty, buyPrice).toMutableList()
        if (status == TradingCycleStatus.HOLDING && holdingQty > 0 && isTrendBreakTriggered(currentBar, prevBar)) {
            signals += Signal.TrendBreak
            return prioritize(signals)
        }
        return signals
    }

    fun detectSignals(tick: PriceTick, holdingQty: Int, buyPrice: Int): List<Signal> {
        if (holdingQty <= 0) return emptyList()

        val signals = mutableListOf<Signal>()
        val price = tick.price

        when (status) {
            TradingCycleStatus.BUYING -> {
                if (isStopLossTriggered(price, buyPrice)) signals += Signal.StopLoss
                if (buyAttempt < 3 && isMidwayTakeProfitTriggered(price, buyPrice)) signals += Signal.MidwayTakeProfit
            }

            TradingCycleStatus.HOLDING -> {
                if (isStopLossTriggered(price, buyPrice)) signals += Signal.StopLoss
                listOf(2, 3, 5).forEach { stagePct ->
                    if (isTpStageTriggered(price, stagePct, buyPrice)) signals += Signal.TpStage(stagePct)
                }
                if (isBreakevenTriggered(price, buyPrice)) signals += Signal.Breakeven
            }

            else -> Unit
        }
        return prioritize(signals)
    }

    fun isStopLossTriggered(currentPrice: Int, buyPrice: Int): Boolean =
        currentPrice <= (buyPrice * (1.0 + stopLossPct.toDouble() / 100.0)).toInt()

    fun isMidwayTakeProfitTriggered(currentPrice: Int, buyPrice: Int): Boolean =
        currentPrice >= ceil(buyPrice * (1.0 + midwayProfitPct.toDouble() / 100.0)).toInt()

    fun isTpStageTriggered(currentPrice: Int, stagePct: Int, buyPrice: Int): Boolean {
        val bit = stagePctToBit(stagePct)
        if (tpStagesFired and bit != 0) return false
        return currentPrice >= ceil(buyPrice * (1.0 + stagePct / 100.0)).toInt()
    }

    fun isBreakevenTriggered(currentPrice: Int, buyPrice: Int): Boolean {
        if (!breakevenArmed) return false
        return currentPrice <= buyPrice
    }

    fun isTrendBreakTriggered(currentBar: Bar, prevBar: Bar): Boolean {
        if (!trendBreakArmed) return false
        return currentBar.closePrice < prevBar.openPrice
    }

    fun splitSellQty(holdingQty: Int): Pair<Int, Int> {
        require(holdingQty >= 0) { "보유 수량은 0 이상이어야 합니다: $holdingQty" }
        // floor만 쓰면 holdingQty * ratio < 1인 작은 보유(3·4주에서 20% = 0.6·0.8)에서 0이 나와
        // 분할 익절 단계 비트는 켜지면서 실 매도가 0건이 되는 침묵 실패가 발생.
        // 보유가 1주 이상이면 최소 1주는 매도하도록 보장 — 잔량은 자연스럽게 줄어들면서 추적 보존.
        val raw = (holdingQty * splitSellRatio.toDouble()).toInt()
        val splitQty = if (holdingQty > 0) raw.coerceAtLeast(1) else 0
        val remainder = holdingQty - splitQty
        return Pair(splitQty, remainder)
    }

    fun calculateBuyPrice(executions: List<Execution>, sellCostRate: Double): Int {
        require(executions.isNotEmpty()) { "체결 목록이 비어있습니다." }
        val totalCost = executions.sumOf { it.executedPrice.toLong() * it.executedQty + it.fee }
        val totalQty = executions.sumOf { it.executedQty }
        val avgCost = totalCost.toDouble() / totalQty
        return ceil(avgCost * (1.0 + sellCostRate)).toInt()
    }

    private fun prioritize(signals: List<Signal>): List<Signal> {
        if (signals.any { it is Signal.StopLoss }) return listOf(Signal.StopLoss)
        return signals.sortedBy { it.priority }
    }

    private fun stagePctToBit(stagePct: Int): Int = when (stagePct) {
        2 -> 0b001
        3 -> 0b010
        5 -> 0b100
        else -> throw IllegalArgumentException("유효하지 않은 stagePct: $stagePct")
    }

    companion object {
        const val MAX_BUY_ATTEMPT = 3
    }
}
