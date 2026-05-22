package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.library.format.FormatUtils

class MarketCapFilter(
    private val criteria: LeadingStockCriteriaProperties,
) : StockFilter {
    override val name = "시가총액"

    override fun filter(stock: LeadingStockSnapshot): Boolean =
        stock.marketCap >= criteria.minMarketCap

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        // stock.marketCap, criteria.minMarketCap 모두 억원 단위 → 원 단위로 환산해 표시
        val marketCapInWon = stock.marketCap * 100_000_000
        val criteriaInWon = criteria.minMarketCap * 100_000_000
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "${FormatUtils.formatKoreanMoney(criteriaInWon)} 이상",
            actualValue = FormatUtils.formatKoreanMoney(marketCapInWon),
            passed = filter(stock),
        )
    }
}
