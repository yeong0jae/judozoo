package at.backend.overseasleadingstock.presentation.response

data class OverseasStockRankItem(
    val rank: Int,
    val exchange: String,
    val symbol: String,
    val name: String,
    val ename: String,
    val price: Double,
    val diff: Double,
    val rate: Double,
    val tradingValue: Double,
)
