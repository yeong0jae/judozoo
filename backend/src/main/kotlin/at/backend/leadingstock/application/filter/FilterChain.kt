package at.backend.leadingstock.application.filter

import at.backend.leadingstock.domain.LeadingStockSnapshot
import org.slf4j.LoggerFactory

class FilterChain(private val filters: List<StockFilter>) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun apply(stocks: List<LeadingStockSnapshot>): List<LeadingStockSnapshot> {
        var remaining = stocks
        for (filter in filters) {
            val before = remaining.size
            remaining = remaining.filter { filter.filter(it) }
            log.debug("[{}] {} -> {} stocks", filter.name, before, remaining.size)
            if (remaining.isEmpty()) break
        }
        return remaining
    }
}
