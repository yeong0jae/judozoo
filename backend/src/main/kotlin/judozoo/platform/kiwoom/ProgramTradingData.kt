package judozoo.platform.kiwoom

/** 종목 하루 프로그램 매매(백만원). */
data class ProgramTradingData(
    val stockCode: String,
    val date: String,
    val programSellAmount: Long,
    val programBuyAmount: Long,
    val programNetBuyAmount: Long,
)
