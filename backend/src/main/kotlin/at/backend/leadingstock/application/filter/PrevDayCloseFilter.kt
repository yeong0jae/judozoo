package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.DailyCandle
import at.backend.leadingstock.domain.LeadingStockSnapshot

class PrevDayCloseFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val dailyCandleProvider: (String) -> List<DailyCandle> = { emptyList() },
) : StockFilter {
    override val name = "전일 등락률"

    override fun filter(stock: LeadingStockSnapshot): Boolean {
        val candles = dailyCandleProvider(stock.stockCode)
        if (candles.size < 2) return false
        // candles[0] = 오늘, candles[1] = 어제
        return candles[1].changeRate <= criteria.maxPrevCloseChangeRate
    }

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val candles = dailyCandleProvider(stock.stockCode)
        val actualValue = if (candles.size >= 2) "%+.2f%%".format(candles[1].changeRate) else "데이터 부족"
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "${criteria.maxPrevCloseChangeRate}% 이하",
            actualValue = actualValue,
            passed = filter(stock),
        )
    }
}
