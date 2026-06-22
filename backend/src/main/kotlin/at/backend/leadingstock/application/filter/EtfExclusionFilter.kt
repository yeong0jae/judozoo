package at.backend.leadingstock.application.filter

import at.backend.leadingstock.domain.LeadingStockSnapshot

/**
 * ETF/ETN을 제외해 개별 종목만 통과시킨다. 키움 응답에 종목 유형 필드가 없어
 * 종목명 prefix(KODEX/TIGER 등 운용사 브랜드)로 거른다.
 */
class EtfExclusionFilter : StockFilter {
    override val name = "ETF/ETN 제외"

    override fun filter(stock: LeadingStockSnapshot): Boolean = !isEtfOrEtn(stock.stockName)

    override fun evaluate(stock: LeadingStockSnapshot) = FilterEvaluationResult(
        filterName = name,
        criteriaDescription = "개별 종목만 (ETF/ETN 제외)",
        actualValue = if (filter(stock)) "개별 종목" else "ETF/ETN",
        passed = filter(stock),
    )

    private fun isEtfOrEtn(name: String): Boolean {
        if (BRAND_PREFIXES.any { name.startsWith(it) }) return true
        // 뒤에 ETN이 붙는 케이스 ("한투 ETN 코스피200 H" 등)
        if (name.contains(" ETN")) return true
        return false
    }

    companion object {
        // 운용사 브랜드 — ETF/ETN 종목명은 보통 "{브랜드} {지수/테마}" 형태
        private val BRAND_PREFIXES = listOf(
            "KODEX ", "TIGER ", "KOSEF ", "KBSTAR ", "ARIRANG ",
            "ACE ", "HANARO ", "SOL ", "RISE ", "WOORI ",
            "KOACT ", "히어로즈 ", "PLUS ", "MASTER ",
        )
    }
}
