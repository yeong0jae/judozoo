package at.backend.trading.application.result

import at.backend.trading.domain.cycle.TradingCycle

data class TradingCancelResult(val status: String, val closeReason: String? = null) {
    companion object {
        fun from(cycle: TradingCycle) = TradingCancelResult(
            status = cycle.status.name,
            closeReason = cycle.closeReason?.name,
        )
    }
}
