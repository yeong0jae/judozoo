package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot

class ThemeRankFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val themeRankProvider: (String) -> Int?,
) : StockFilter {
    override val name = "테마순위"

    override fun filter(stock: LeadingStockSnapshot): Boolean {
        val rank = themeRankProvider(stock.stockCode)
        // 테마가 없으면 통과 — 노이즈 배제 목적이 아니라 "테마 강하면 좋다" 정도
        return rank == null || rank <= criteria.maxThemeRank
    }

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val rank = themeRankProvider(stock.stockCode)
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "상위 ${criteria.maxThemeRank}위 이내",
            actualValue = if (rank != null) "${rank}위" else "테마 없음",
            passed = filter(stock),
        )
    }
}
