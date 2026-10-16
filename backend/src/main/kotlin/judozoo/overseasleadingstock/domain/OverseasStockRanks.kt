package judozoo.overseasleadingstock.domain

import judozoo.library.Ranking

class OverseasStockRanks(private val stocks: List<OverseasStockRank>) {
    fun leaders(count: Int): List<OverseasStockRank> =
        Ranking.topBalanced(stocks.filter { it.rate > 0 }, { it.tradingValue }, { it.rate }, count, TRADING_VALUE_WEIGHT)

    companion object {
        const val TRADING_VALUE_WEIGHT = 0.55
    }
}
