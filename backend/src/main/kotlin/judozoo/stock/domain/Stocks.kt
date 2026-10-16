package judozoo.stock.domain

/** 종목 카탈로그 전체를 감싸는 일급 컬렉션. 검색 책임을 컬렉션이 직접 가진다. */
class Stocks(private val stocks: List<Stock>) {
    val size: Int get() = stocks.size

    /** 종목명/코드로 검색. 관련도 순 — 1) 종목명 시작 2) 코드 시작 3) 종목명 포함. 같으면 종목명 사전순. 최대 [limit]건. */
    fun search(query: String, limit: Int): List<Stock> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return stocks.filter { it.matches(q) }
            .sortedWith(compareBy<Stock> { it.relevance(q) }.thenBy { it.name })
            .take(limit)
    }

    /**
     * 단축코드로 한 종목. 거래소 접미사(`009150_AL`)가 붙어 와도 앞쪽만 본다 —
     * 시세 쪽은 SOR 통합 코드를 쓰지만 카탈로그는 단축코드로 적재된다.
     */
    fun find(shortCode: String): Stock? {
        val code = shortCode.substringBefore("_").trim()
        return stocks.firstOrNull { it.shortCode == code }
    }

    companion object {
        val EMPTY = Stocks(emptyList())
    }
}
