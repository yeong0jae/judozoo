package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.library.format.FormatUtils

class ProgramNetBuyFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val programNetBuyProvider: (String) -> Long,
) : StockFilter {
    override val name = "프로그램순매수"

    override fun filter(stock: LeadingStockSnapshot): Boolean =
        programNetBuyProvider(stock.stockCode) >= criteria.minProgramNetBuy

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val netBuy = programNetBuyProvider(stock.stockCode)
        // netBuy, criteria.minProgramNetBuy 모두 백만원 단위 → 원 단위로 환산해 표시
        val netBuyInWon = netBuy * 1_000_000
        val criteriaInWon = criteria.minProgramNetBuy * 1_000_000
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "${FormatUtils.formatKoreanMoney(criteriaInWon)} 이상",
            actualValue = FormatUtils.formatKoreanMoneyWithMan(netBuyInWon),
            passed = netBuy >= criteria.minProgramNetBuy,
        )
    }
}
