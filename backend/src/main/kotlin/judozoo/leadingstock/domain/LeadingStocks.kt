package judozoo.leadingstock.domain

import judozoo.library.Ranking

class LeadingStocks(private val stocks: List<LeadingStockSnapshot>) {
    fun leaders(count: Int): List<LeadingStockSnapshot> = Ranking.topBalanced(
        stocks.filter { it.priceChangeRate > 0 }, { it.tradingValueForRanking }, { it.rateForRanking }, count, TRADING_VALUE_WEIGHT,
    )

    fun limitUps(): List<LeadingStockSnapshot> = stocks.filter { it.limitUp }

    companion object {
        const val TRADING_VALUE_WEIGHT = 0.65
    }
}
