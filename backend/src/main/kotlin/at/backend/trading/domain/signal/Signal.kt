package at.backend.trading.domain.signal

import at.backend.market.domain.Bar
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

    data class TpStage(val pct: Int) : Signal() {
        override val priority = 2

        init {
            require(pct in setOf(2, 3, 5)) { "TP 단계 비율은 2/3/5% 중 하나여야 합니다: $pct" }
        }
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

    data object Cancel : Signal() {
        override val priority = 0
    }
}
