package at.backend.trading.domain.execution

import kotlin.math.ceil

class Executions(
    private val list: List<Execution>
) {
    init {
        require(list.isNotEmpty()) { "체결 목록이 비어있습니다." }
    }

    fun calculateBuyPrice(sellCostRate: Double): Int {
        val totalCost = list.sumOf { it.executedPrice.toLong() * it.executedQty + it.fee }
        val totalQty = list.sumOf { it.executedQty }
        val avgCost = totalCost.toDouble() / totalQty
        return ceil(avgCost * (1.0 + sellCostRate)).toInt()
    }
}
