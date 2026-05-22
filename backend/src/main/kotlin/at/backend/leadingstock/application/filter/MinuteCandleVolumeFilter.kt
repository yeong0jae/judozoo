package at.backend.leadingstock.application.filter

import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.leadingstock.domain.MinuteCandle
import at.backend.library.format.FormatUtils

class MinuteCandleVolumeFilter(
    private val criteria: LeadingStockCriteriaProperties,
    private val minuteCandleProvider: (String) -> List<MinuteCandle>,
) : StockFilter {
    override val name = "1분봉거래대금"

    override fun filter(stock: LeadingStockSnapshot): Boolean {
        val candles = minuteCandleProvider(stock.stockCode)
        if (candles.isEmpty()) return false
        val latest = candles.first()
        if (latest.tradingValue < criteria.minMinuteTradingValue) return false
        val avg = candles.map { it.tradingValue }.average()
        if (avg <= 0) return false
        val increaseRate = (latest.tradingValue.toDouble() / avg) * 100
        return increaseRate >= criteria.minMinuteVolumeIncreaseRate
    }

    override fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult {
        val candles = minuteCandleProvider(stock.stockCode)
        val actualValue = if (candles.isNotEmpty()) {
            val latest = candles.first()
            val avg = candles.map { it.tradingValue }.average()
            val increaseRate = if (avg > 0) (latest.tradingValue.toDouble() / avg) * 100 else 0.0
            "${FormatUtils.formatKoreanMoney(latest.tradingValue)} (%.0f%%)".format(increaseRate)
        } else "데이터 없음"
        return FilterEvaluationResult(
            filterName = name,
            criteriaDescription = "${FormatUtils.formatKoreanMoney(criteria.minMinuteTradingValue)} 이상 & 증가율 ${criteria.minMinuteVolumeIncreaseRate.toInt()}%",
            actualValue = actualValue,
            passed = filter(stock),
        )
    }
}
