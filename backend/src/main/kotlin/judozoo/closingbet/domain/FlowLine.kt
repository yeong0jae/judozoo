package judozoo.closingbet.domain

data class FlowLine(
    val today: Long,
    val week: Long,
    val futuresToday: Long,
    val futuresWeek: Long,
    val closing: Long,
    val afterMarket: Long,
) {
    val late: Long get() = closing + afterMarket

    fun values(): List<Long> = listOf(today, week, futuresToday, futuresWeek, closing, afterMarket)
}
