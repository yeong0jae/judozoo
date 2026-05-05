package at.backend.trading.domain

class TradingValidationException(val errorCode: ErrorCode) : RuntimeException(errorCode.name) {

    enum class ErrorCode {
        INVALID_PARAMETER,
        STOCK_NOT_FOUND,
        PRICE_BELOW_ONE_SHARE,
        INSUFFICIENT_BALANCE,
        DUPLICATE_COMMAND,
        CUTOFF_PASSED,
        HOLIDAY,
        OUT_OF_TRADING_HOURS,
        COMMAND_GATE_CLOSED,
    }
}
