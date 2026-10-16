package judozoo.stock.domain

class Stocks(private val stocks: List<Stock>) {
    val size: Int get() = stocks.size

    fun search(query: String, limit: Int): List<Stock> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return stocks.filter { it.matches(q) }
            .sortedWith(compareBy<Stock> { it.relevance(q) }.thenBy { it.name })
            .take(limit)
    }

    fun find(shortCode: String): Stock? {
        val code = shortCode.substringBefore("_").trim()
        return stocks.firstOrNull { it.shortCode == code }
    }

    companion object {
        val EMPTY = Stocks(emptyList())
    }
}
