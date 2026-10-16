package judozoo.leadingstock.domain

/** 누적 순매수의 방향. 중립(0)은 방향 없음(null)으로 본다. */
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
