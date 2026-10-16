package judozoo.market.domain

enum class CandleInterval(val code: String) {
    DAILY("1d"),
    MINUTE("1m"),
    ;

    companion object {
        fun of(code: String): CandleInterval? = entries.firstOrNull { it.code == code }
    }
}
