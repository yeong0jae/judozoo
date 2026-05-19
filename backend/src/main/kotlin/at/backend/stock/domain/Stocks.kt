package at.backend.stock.domain

/** 종목 카탈로그 전체를 감싸는 1급 컬렉션. 검색 책임을 컬렉션이 직접 가진다. */
class Stocks(private val list: List<Stock>) {

    val size: Int get() = list.size

    /**
     * 종목명/코드로 검색한다. 관련도 순으로 정렬:
     * 1) 종목명이 질의로 시작  2) 종목코드가 질의로 시작  3) 종목명에 질의 포함.
     * 동일 관련도면 종목명 사전순. 최대 [limit]건.
     */
    fun search(query: String, limit: Int): List<Stock> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return list.asSequence()
            .filter { it.matches(q) }
            .sortedWith(compareBy({ relevance(it, q) }, { it.name }))
            .take(limit)
            .toList()
    }

    private fun relevance(stock: Stock, q: String): Int = when {
        stock.name.lowercase().startsWith(q) -> 0
        stock.shortCode.lowercase().startsWith(q) -> 1
        else -> 2
    }
}
