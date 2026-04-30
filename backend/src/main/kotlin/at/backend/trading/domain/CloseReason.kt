package at.backend.trading.domain

enum class CloseReason {
    TAKE_PROFIT,
    STOP_LOSS,
    BREAKEVEN,
    TREND_BREAK,
    MARKET_CLOSE,
    CANCELLED,
    NO_FILL,
    UNCLOSED,
}
