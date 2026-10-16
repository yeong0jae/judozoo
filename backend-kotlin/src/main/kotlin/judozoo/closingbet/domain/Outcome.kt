package judozoo.closingbet.domain

/** 한 베팅의 결과. [pnl]은 원, [rate]는 %. */
data class Outcome(val pnl: Long, val rate: Double) {
    companion object {
        /** 수수료·세금은 빼지 않는다. 0.5원 단위 매도가 때문에 수익금은 원 단위로 반올림한다 — Python `round`처럼 짝수 쪽으로. */
        fun of(shares: Int, buyPrice: Long, sell: Double): Outcome =
            Outcome(Math.rint(shares * (sell - buyPrice)).toLong(), (sell / buyPrice - 1) * 100)
    }
}
