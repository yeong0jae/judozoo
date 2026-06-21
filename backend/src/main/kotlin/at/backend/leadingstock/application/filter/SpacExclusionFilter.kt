package at.backend.leadingstock.application.filter

import at.backend.leadingstock.domain.LeadingStockSnapshot

/**
 * 스팩(기업인수목적회사)을 제외해 개별 사업 종목만 통과시킨다.
 * 키움 응답에 종목 유형 필드가 없어 종목명에 "스팩"이 들어가는지로 거른다
 * (예: "메리츠제2호스팩", "교보15호스팩").
 */
class SpacExclusionFilter : StockFilter {
    override val name = "스팩 제외"

    override fun filter(stock: LeadingStockSnapshot): Boolean = !stock.stockName.contains(SPAC_KEYWORD)

    override fun evaluate(stock: LeadingStockSnapshot) = FilterEvaluationResult(
        filterName = name,
        criteriaDescription = "개별 종목만 (스팩 제외)",
        actualValue = if (filter(stock)) "개별 종목" else "스팩",
        passed = filter(stock),
    )

    companion object {
        private const val SPAC_KEYWORD = "스팩"
    }
}
