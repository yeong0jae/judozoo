package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.DailyCandle
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.library.format.FormatUtils

class OpeningPriceFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val dailyCandleProvider: (String) -> List<DailyCandle> = { emptyList() },
) : StockFilter {
    override val name = "시초가"

    override fun filter(stock: LeadingStockSnapshot): Boolean {
        val candles = dailyCandleProvider(stock.stockCode)
        if (candles.size < 2) return false
        val today = candles[0]
        val yesterday = candles[1]
        if (today.openPrice <= 0 || yesterday.closePrice <= 0) return false
        val openChangeRate = ((today.openPrice - yesterday.closePrice).toDouble() / yesterday.closePrice) * 100
        return openChangeRate <= criteria.maxOpeningPriceChangeRate
    }

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val candles = dailyCandleProvider(stock.stockCode)
        val actualValue = if (candles.size >= 2) {
            val today = candles[0]
            val yesterday = candles[1]
            if (today.openPrice > 0 && yesterday.closePrice > 0) {
                val openChangeRate = ((today.openPrice - yesterday.closePrice).toDouble() / yesterday.closePrice) * 100
                "${FormatUtils.formatPrice(today.openPrice)} (%+.2f%%)".format(openChangeRate)
            } else "시초가 없음"
        } else "데이터 부족"
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "시초가 ${criteria.maxOpeningPriceChangeRate}% 이하",
            actualValue = actualValue,
            passed = filter(stock),
        )
    }
}
