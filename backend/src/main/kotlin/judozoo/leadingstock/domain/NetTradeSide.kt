package judozoo.leadingstock.domain

enum class NetTradeSide {
    BUY,
    SELL,
    ;

    fun opposite(): NetTradeSide = if (this == BUY) SELL else BUY

    companion object {
        fun of(netEok: Long): NetTradeSide? = when {
            netEok > 0 -> BUY
            netEok < 0 -> SELL
            else -> null
        }
    }
}
