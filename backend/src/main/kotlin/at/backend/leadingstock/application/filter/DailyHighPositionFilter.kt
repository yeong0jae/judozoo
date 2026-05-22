package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.DailyCandle
import at.backend.leadingstock.domain.LeadingStockSnapshot

class DailyHighPositionFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val dailyCandleProvider: (String) -> List<DailyCandle>,
) : StockFilter {
    override val name = "최근 고가 대비 현재가"

    override fun filter(stock: LeadingStockSnapshot): Boolean {
        val candles = dailyCandleProvider(stock.stockCode)
        if (candles.isEmpty()) return false
        val maxHigh = candles.maxOf { it.highPrice }
        if (maxHigh <= 0) return false
        val dropRate = ((stock.currentPrice - maxHigh).toDouble() / maxHigh) * 100
        return dropRate >= criteria.maxHighPositionDropRate
    }

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val candles = dailyCandleProvider(stock.stockCode)
        val actualValue = if (candles.isNotEmpty()) {
            val maxHigh = candles.maxOf { it.highPrice }
            if (maxHigh > 0) {
                val dropRate = ((stock.currentPrice - maxHigh).toDouble() / maxHigh) * 100
                "%.2f%%".format(dropRate)
            } else "데이터 없음"
        } else "데이터 없음"
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "최근 일봉 60개 중 고가 대비 현재가 ${criteria.maxHighPositionDropRate}% 이상",
            actualValue = actualValue,
            passed = filter(stock),
        )
    }
}
