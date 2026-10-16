package judozoo.leadingstock.domain

import judozoo.stock.domain.Market

data class MarketSignalScale(val stepEok: Long, val reversalEok: Long) {
    val bufferEok: Long get() = stepEok / 10

    companion object {
        fun of(market: Market): MarketSignalScale = when (market) {
            Market.KOSPI -> MarketSignalScale(stepEok = 10_000, reversalEok = 1_000)
            Market.KOSDAQ -> MarketSignalScale(stepEok = 1_000, reversalEok = 100)
        }
    }
}
