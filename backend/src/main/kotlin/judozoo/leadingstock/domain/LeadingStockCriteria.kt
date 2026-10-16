package judozoo.leadingstock.domain

data class LeadingStockCriteria(
    val minMarketCap: Long = 3000,
    val maxTradingValueRank: Int = 35,
    val minDailyPriceChangeRate: Double = 7.0,
    val maxHighPositionDropRate: Double = -5.0,
    val minProgramNetBuy: Long = 0,
    val maxPrevCloseChangeRate: Double = 25.0,
    val maxOpeningPriceChangeRate: Double = 7.0,
)
