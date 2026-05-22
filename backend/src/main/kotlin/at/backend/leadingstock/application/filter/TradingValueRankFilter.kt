package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot

class TradingValueRankFilter(
    private val criteria: LeadingStockCriteriaProperties,
) : StockFilter {
    override val name = "거래대금순위"

    override fun filter(stock: LeadingStockSnapshot): Boolean =
        stock.tradingValueRank <= criteria.maxTradingValueRank

    override fun evaluate(stock: LeadingStockSnapshot) = FilterEvaluationResult(
        filterName = name,
        criteriaDescription = "상위 ${criteria.maxTradingValueRank}위 이내",
        actualValue = "${stock.tradingValueRank}위",
        passed = filter(stock),
    )
}
