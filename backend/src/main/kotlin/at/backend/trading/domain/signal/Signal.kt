package at.backend.trading.domain.signal

import at.backend.trading.domain.Bar
import java.time.Instant

sealed class Signal {

    abstract val priority: Int

    open fun isAlive(
        currentPrice: Int,
        buyPrice: Int,
        currentBar: Bar?,
        clock: Instant,
    ): Boolean = true

    data object StopLoss : Signal() {
        override val priority = 1
    }

    data object MidwayTakeProfit : Signal() {
        override val priority = 2
    }

    data class TpStage(val pct: Int) : Signal() {
        override val priority = 2
    }

    data object Breakeven : Signal() {
        override val priority = 2
        override fun isAlive(
            currentPrice: Int,
            buyPrice: Int,
            currentBar: Bar?,
            clock: Instant,
        ): Boolean = currentPrice <= buyPrice
    }

    data object TrendBreak : Signal() {
        override val priority = 2
        override fun isAlive(
            currentPrice: Int,
            buyPrice: Int,
            currentBar: Bar?,
            clock: Instant,
        ): Boolean = currentBar != null && clock < currentBar.endTime
    }

    data object LimitUp : Signal() {
        override val priority = 2
    }

    data object MarketClose : Signal() {
        override val priority = 0
    }

    data object Cancel : Signal() {
        override val priority = 0
    }
}
