package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.leadingstock.domain.MinuteCandle
import kotlin.math.abs

class MinuteCandleFluctuationFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val minuteCandleProvider: (String) -> List<MinuteCandle>,
) : StockFilter {
    override val name = "1분봉등락률"

    override fun filter(stock: LeadingStockSnapshot): Boolean {
        val candles = minuteCandleProvider(stock.stockCode)
        if (candles.isEmpty()) return false
        val latest = candles.first()
        if (latest.openPrice <= 0) return false
        val rate = abs(((latest.closePrice - latest.openPrice).toDouble() / latest.openPrice) * 100)
        return rate <= criteria.maxMinuteFluctuationRate
    }

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val candles = minuteCandleProvider(stock.stockCode)
        val actualValue = if (candles.isNotEmpty()) {
            val latest = candles.first()
            if (latest.openPrice > 0) {
                val rate = abs(((latest.closePrice - latest.openPrice).toDouble() / latest.openPrice) * 100)
                "%.2f%%".format(rate)
            } else "데이터 없음"
        } else "데이터 없음"
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "${criteria.maxMinuteFluctuationRate}% 이하",
            actualValue = actualValue,
            passed = filter(stock),
        )
    }
}
