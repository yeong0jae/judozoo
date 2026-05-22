package at.backend.leadingstock.application.filter

import at.backend.leadingstock.domain.LeadingStockSnapshot

class PriceAboveOpenFilter : StockFilter {
    override val name = "시가 대비 현재가"

    override fun filter(stock: LeadingStockSnapshot): Boolean =
        stock.openingPrice > 0 && stock.currentPrice >= stock.openingPrice

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val actualValue = if (stock.openingPrice > 0) {
            val rate = ((stock.currentPrice - stock.openingPrice).toDouble() / stock.openingPrice) * 100
            "%+.2f%%".format(rate)
        } else {
            "시가 없음"
        }
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "현재가 ≥ 시가",
            actualValue = actualValue,
            passed = filter(stock),
        )
    }
}
