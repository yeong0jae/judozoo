package judozoo.closingbet.domain

data class Outcome(val pnl: Long, val rate: Double) {
    companion object {
        fun of(shares: Int, buyPrice: Long, sell: Double): Outcome =
            Outcome(Math.rint(shares * (sell - buyPrice)).toLong(), (sell / buyPrice - 1) * 100)
    }
}
