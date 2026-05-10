package at.backend.trading.domain.cycle

enum class TradingCycleStatus {
    INITIATED,
    BUYING,
    HOLDING,
    LIQUIDATING,
    CLOSED;

    companion object {
        val ACTIVE = listOf(INITIATED, BUYING, HOLDING)
        val OPEN = listOf(INITIATED, BUYING, HOLDING, LIQUIDATING)
    }
}
