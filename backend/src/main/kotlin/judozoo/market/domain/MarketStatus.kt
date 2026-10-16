package judozoo.market.domain

data class MarketStatus(val holiday: Boolean, val tradingHours: Boolean) {
    val inSession: Boolean get() = !holiday && tradingHours
}
