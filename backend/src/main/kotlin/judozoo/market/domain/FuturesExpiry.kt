package judozoo.market.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object FuturesExpiry {
    private val YEAR_MONTH = Regex("""(\d{6})""")

    fun of(contractName: String): LocalDate? {
        val ym = YEAR_MONTH.find(contractName)?.value ?: return null
        val first = runCatching { LocalDate.of(ym.substring(0, 4).toInt(), ym.substring(4, 6).toInt(), 1) }.getOrNull() ?: return null
        return first.with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.THURSDAY))
    }
}
