package at.backend.trading.domain.signal

sealed class Signal {
    abstract val priority: Int

    data object StopLoss : Signal() { override val priority = 1 }
    data object MidwayTakeProfit : Signal() { override val priority = 2 }
    data class TpStage(val pct: Int) : Signal() { override val priority = 2 }
    data object Breakeven : Signal() { override val priority = 2 }
    data object TrendBreak : Signal() { override val priority = 2 }
    data object LimitUp : Signal() { override val priority = 2 }
    data object MarketClose : Signal() { override val priority = 0 }
    data object Cancel : Signal() { override val priority = 0 }
}
