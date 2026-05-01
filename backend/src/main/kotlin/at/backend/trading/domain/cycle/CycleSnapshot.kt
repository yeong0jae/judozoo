package at.backend.trading.domain.cycle

import at.backend.trading.domain.Bar
import at.backend.trading.domain.PriceTick
import at.backend.trading.domain.signal.Signal
import kotlin.math.ceil

data class CycleSnapshot(
    val state: CycleState,         // 현재 사이클 상태 (Buying / Holding 등)
    val holdingQty: Int,           // 보유 수량. 0이면 가격 기반 시그널 평가 보류
    val buyPrice: Int,             // 손익 0 기준 매수가 (매도 비용률 반영)
    val tpStagesFired: Int,        // TP 단계 발동 비트플래그 (b0=2%, b1=3%, b2=5%)
    val breakevenArmed: Boolean,   // 본전 매도 무장 여부 (+본전기준% 도달 시 true)
    val trendBreakArmed: Boolean,  // 추세 꺾임 무장 여부 (+5% 도달 시 true)
    val buyAttempt: Int,           // 현재 매수 회차 (1~3)
    val stopLossPct: Double,       // 손절 기준 비율. 음수 (예: -0.02 = -2%)
    val midwayProfitPct: Double,   // 중도 익절 기준 퍼센트. 양수 (예: 3.0 = +3%)
) {

    fun detectSignals(tick: PriceTick, currentBar: Bar, prevBar: Bar): List<Signal> {
        val signals = detectSignals(tick).toMutableList()
        if (state is CycleState.Holding && holdingQty > 0 && isTrendBreakTriggered(currentBar, prevBar)) {
            signals += Signal.TrendBreak
            return prioritize(signals)
        }
        return signals
    }

    fun detectSignals(tick: PriceTick): List<Signal> {
        if (holdingQty <= 0) return emptyList()

        val signals = mutableListOf<Signal>()
        val price = tick.price

        when (state) {
            is CycleState.Buying -> {
                if (isStopLossTriggered(price)) signals += Signal.StopLoss
                if (buyAttempt < 3 && isMidwayTakeProfitTriggered(price)) {
                    signals += Signal.MidwayTakeProfit
                }
            }

            is CycleState.Holding -> {
                if (isStopLossTriggered(price)) signals += Signal.StopLoss
                listOf(2, 3, 5).forEach { stagePct ->
                    if (isTpStageTriggered(price, stagePct)) {
                        signals += Signal.TpStage(stagePct)
                    }
                }
                if (isBreakevenTriggered(price)) signals += Signal.Breakeven
            }

            else -> Unit
        }
        return prioritize(signals)
    }

    private fun prioritize(signals: List<Signal>): List<Signal> {
        // StopLoss 발동 시 다른 모든 시그널 제거 (spec §6.3)
        if (signals.any { it is Signal.StopLoss }) {
            return listOf(Signal.StopLoss)
        }
        return signals.sortedBy { it.priority }
    }

    fun isStopLossTriggered(currentPrice: Int): Boolean =
        currentPrice <= (buyPrice * (1.0 + stopLossPct)).toInt()

    fun isMidwayTakeProfitTriggered(currentPrice: Int): Boolean =
        currentPrice >= ceil(buyPrice * (1.0 + midwayProfitPct / 100.0)).toInt()

    fun isTpStageTriggered(currentPrice: Int, stagePct: Int): Boolean {
        val bit = stagePctToBit(stagePct)
        if (tpStagesFired and bit != 0) return false
        return currentPrice >= ceil(buyPrice * (1.0 + stagePct / 100.0)).toInt()
    }

    fun isBreakevenTriggered(currentPrice: Int): Boolean {
        if (!breakevenArmed) return false
        return currentPrice <= buyPrice
    }

    fun isTrendBreakTriggered(currentBar: Bar, prevBar: Bar): Boolean {
        if (!trendBreakArmed) return false
        return currentBar.closePrice < prevBar.openPrice
    }

    fun splitSellQty(splitSellRatio: Double): Pair<Int, Int> {
        val splitQty = (holdingQty * splitSellRatio).toInt()
        val remainder = holdingQty - splitQty
        return Pair(splitQty, remainder)
    }

    private fun stagePctToBit(stagePct: Int): Int = when (stagePct) {
        2 -> 0b001
        3 -> 0b010
        5 -> 0b100
        else -> throw IllegalArgumentException("유효하지 않은 stagePct: $stagePct")
    }
}
