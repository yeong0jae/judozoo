package at.backend.trading.domain.signal

import at.backend.trading.domain.Bar
import java.time.Instant

object SignalGuard {

    fun isAlive(signal: Signal, currentPrice: Int, buyPrice: Int, currentBar: Bar?, clock: Instant = Instant.now()): Boolean =
        when (signal) {
            Signal.StopLoss -> true
            Signal.Cancel -> true
            Signal.MarketClose -> true
            Signal.MidwayTakeProfit -> true
            is Signal.TpStage -> true
            Signal.Breakeven -> currentPrice <= buyPrice
            Signal.TrendBreak -> currentBar != null && clock < currentBar.endTime
            Signal.LimitUp -> true
        }
}
