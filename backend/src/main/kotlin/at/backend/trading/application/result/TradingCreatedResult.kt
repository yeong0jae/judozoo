package at.backend.trading.application.result

import at.backend.trading.domain.cycle.TradingCycle

data class TradingCreatedResult(val id: Long) {
    companion object {
        fun from(cycle: TradingCycle) = TradingCreatedResult(id = cycle.id)
    }
}
