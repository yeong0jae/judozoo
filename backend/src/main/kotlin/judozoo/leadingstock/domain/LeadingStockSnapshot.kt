package judozoo.leadingstock.domain

data class LeadingStockSnapshot(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val tradingValueRank: Int,
    val accumulatedTradingValue: Long,
    val accumulatedVolume: Long? = null,
    val marketCap: Long = 0,
    val openingPrice: Long = 0,
    val previousClose: Long = 0,
    val highPrice: Long = 0,
    val lowPrice: Long = 0,
    val programNetBuy: Long = 0,
    val limitUp: Boolean = false,
    val wideLimitDay: Boolean = false,
) {
    val code: String get() = stockCode.substringBefore("_")

    val exceedsNormalLimit: Boolean get() = priceChangeRate > NORMAL_LIMIT_RATE

    val isWideLimitDay: Boolean get() = wideLimitDay || exceedsNormalLimit

    val tradingValueForRanking: Double
        get() = if (isWideLimitDay) accumulatedTradingValue / LISTING_TRADING_VALUE_DIVISOR else accumulatedTradingValue.toDouble()

    val rateForRanking: Double
        get() = if (isWideLimitDay) priceChangeRate / LISTING_RATE_DIVISOR else priceChangeRate

    companion object {
        const val NORMAL_LIMIT_RATE = 30.0

        const val LISTING_RATE_DIVISOR = 20.0

        const val LISTING_TRADING_VALUE_DIVISOR = 2.0
    }
}
