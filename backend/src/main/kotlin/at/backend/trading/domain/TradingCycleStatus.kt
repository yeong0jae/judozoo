package at.backend.trading.domain

enum class TradingCycleStatus {
    INITIATED,
    BUYING,
    HOLDING,
    LIQUIDATING,
    CLOSED,
}
