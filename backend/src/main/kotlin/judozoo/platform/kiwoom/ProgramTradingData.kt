package judozoo.platform.kiwoom

data class ProgramTradingData(
    val stockCode: String,
    val date: String,
    val programSellAmount: Long,
    val programBuyAmount: Long,
    val programNetBuyAmount: Long,
)
