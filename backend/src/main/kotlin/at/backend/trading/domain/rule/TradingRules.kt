package at.backend.trading.domain.rule

import at.backend.trading.domain.Bar
import kotlin.math.ceil

object TradingRules {

    fun calcBuyPrice(executions: List<Execution>, sellCostRate: Double): Int {
        require(executions.isNotEmpty()) { "executions must not be empty" }
        val totalCost = executions.sumOf { it.executedPrice.toLong() * it.executedQty + it.fee }
        val totalQty = executions.sumOf { it.executedQty }
        val avgCost = totalCost.toDouble() / totalQty
        return ceil(avgCost * (1.0 + sellCostRate)).toInt()
    }

    fun calcSplitSellQty(holdingQty: Int, splitSellRatio: Double): Pair<Int, Int> {
        val splitQty = (holdingQty * splitSellRatio).toInt()
        val remainder = holdingQty - splitQty
        return Pair(splitQty, remainder)
    }

    fun isStopLossTriggered(currentPrice: Int, buyPrice: Int, stopLossPct: Double): Boolean {
        return currentPrice <= (buyPrice * (1.0 + stopLossPct)).toInt()
    }

    fun isMidwayTakeProfitTriggered(currentPrice: Int, buyPrice: Int, midwayProfitPct: Double): Boolean {
        return currentPrice >= ceil(buyPrice * (1.0 + midwayProfitPct / 100.0)).toInt()
    }

    fun isTpStageTriggered(currentPrice: Int, buyPrice: Int, stagePct: Int, tpStagesFired: Int): Boolean {
        val bit = stagePctToBit(stagePct)
        if (tpStagesFired and bit != 0) return false
        return currentPrice >= ceil(buyPrice * (1.0 + stagePct / 100.0)).toInt()
    }

    fun isBreakevenTriggered(currentPrice: Int, buyPrice: Int, armed: Boolean): Boolean {
        if (!armed) return false
        return currentPrice <= buyPrice
    }

    fun isTrendBreakTriggered(currentBar: Bar, prevBar: Bar, armed: Boolean): Boolean {
        if (!armed) return false
        return currentBar.closePrice < prevBar.openPrice
    }

    private fun stagePctToBit(stagePct: Int): Int = when (stagePct) {
        2 -> 0b001
        3 -> 0b010
        5 -> 0b100
        else -> throw IllegalArgumentException("Unknown stagePct: $stagePct")
    }
}
