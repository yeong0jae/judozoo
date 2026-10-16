package judozoo.overseasleadingstock.domain

data class OverseasStockRank(
    val rank: Int,
    val exchange: String,
    val symbol: String,
    val name: String,
    val ename: String,
    val price: Double,
    val diff: Double,
    val rate: Double,
    val tradingValue: Double,
) {
    val isEtf: Boolean get() = ename.uppercase().let { upper -> ETF_KEYWORDS.any { it in upper } }

    val previousClose: Double get() = price - diff

    fun ranked(rank: Int) = copy(rank = rank)

    companion object {
        private val ETF_KEYWORDS = listOf(
            " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
            "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
        )
    }
}
