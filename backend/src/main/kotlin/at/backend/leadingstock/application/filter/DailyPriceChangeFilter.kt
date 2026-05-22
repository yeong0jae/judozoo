package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot

class DailyPriceChangeFilter(
    private val criteria: LeadingStockCriteriaProperties,
) : StockFilter {
    override val name = "당일등락률"

    override fun filter(stock: LeadingStockSnapshot): Boolean =
        stock.priceChangeRate >= criteria.minDailyPriceChangeRate

    override fun evaluate(stock: LeadingStockSnapshot) = FilterEvaluationResult(
        filterName = name,
        criteriaDescription = "${criteria.minDailyPriceChangeRate}% 이상",
        actualValue = "%+.2f%%".format(stock.priceChangeRate),
        passed = filter(stock),
    )
}
