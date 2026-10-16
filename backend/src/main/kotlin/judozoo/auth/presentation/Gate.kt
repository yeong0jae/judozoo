package judozoo.auth.presentation

object Gate {
    private const val SEG = "[^/]+"

    private val publicPatterns = listOf(
        "/api/leading-stocks/candidates",
        "/api/leading-stocks/leaders",
        "/api/overseas-leading-stocks/leaders",
        "/api/leader-calendar",
        "/api/leader-timeline",
        "/api/insight/reasons",
        "/api/closingbet/now",
        "/api/closingbet/stocks",
        "/api/closingbet/feed",
        "/api/closingbet/rounds/$SEG",
        "/api/leading-stocks/signal-events",
        "/api/leading-stocks/breakout-radar",
        "/api/overseas-leading-stocks/candidates",
        "/api/market/calendar/status",
        "/api/market/kospi",
        "/api/market/kosdaq",
        "/api/market/nasdaq/quote",
        "/api/market/macro/quotes",
        "/api/market/$SEG/candles",
        "/api/market/futures/$SEG/quote",
        "/api/market/futures/$SEG/candles",
        "/api/auth/.*",
    )

    private val public = Regex("(?:" + publicPatterns.joinToString("|") { "(?:$it)" } + ")/?")

    fun isPublic(path: String): Boolean = public.matches(path)
}
