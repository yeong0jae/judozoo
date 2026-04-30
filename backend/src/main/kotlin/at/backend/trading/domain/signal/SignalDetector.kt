package at.backend.trading.domain.signal

import at.backend.trading.domain.Bar
import at.backend.trading.domain.PriceTick
import at.backend.trading.domain.cycle.CycleState
import at.backend.trading.domain.rule.TradingRules

object SignalDetector {

    fun detect(
        tick: PriceTick,
        snapshot: CycleSnapshot,
        currentBar: Bar? = null,
        prevBar: Bar? = null,
    ): List<Signal> {
        // 보유 수량이 0이면 가격 기반 시그널 평가 보류
        if (snapshot.holdingQty <= 0) return emptyList()

        val signals = mutableListOf<Signal>()
        val price = tick.price
        val buyPrice = snapshot.buyPrice

        when (snapshot.state) {
            is CycleState.Buying -> {
                if (TradingRules.isStopLossTriggered(price, buyPrice, snapshot.stopLossPct)) {
                    signals += Signal.StopLoss
                }
                if (snapshot.buyAttempt < 3 &&
                    TradingRules.isMidwayTakeProfitTriggered(price, buyPrice, snapshot.midwayProfitPct)
                ) {
                    signals += Signal.MidwayTakeProfit
                }
            }

            is CycleState.Monitoring -> {
                if (TradingRules.isStopLossTriggered(price, buyPrice, snapshot.stopLossPct)) {
                    signals += Signal.StopLoss
                }
                listOf(2, 3, 5).forEach { stagePct ->
                    if (TradingRules.isTpStageTriggered(price, buyPrice, stagePct, snapshot.tpStagesFired)) {
                        signals += Signal.TpStage(stagePct)
                    }
                }
                if (TradingRules.isBreakevenTriggered(price, buyPrice, snapshot.breakevenArmed)) {
                    signals += Signal.Breakeven
                }
                if (currentBar != null && prevBar != null &&
                    TradingRules.isTrendBreakTriggered(currentBar, prevBar, snapshot.trendBreakArmed)
                ) {
                    signals += Signal.TrendBreak
                }
            }

            else -> Unit
        }

        // StopLoss 발동 시 다른 모든 시그널 제거 (spec §6.3)
        if (signals.any { it is Signal.StopLoss }) {
            return listOf(Signal.StopLoss)
        }

        return signals.sortedBy { it.priority }
    }
}
