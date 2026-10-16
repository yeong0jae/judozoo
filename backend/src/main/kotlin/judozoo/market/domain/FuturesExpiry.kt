package judozoo.market.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** KRX 지수선물 만기. */
object FuturesExpiry {
    private val YEAR_MONTH = Regex("""(\d{6})""")

    /** 근월물 이름("F 202609")의 만기월 **둘째 목요일** = KRX 파생 최종거래일. 이름에서 월을 못 읽으면 null. */
    fun of(contractName: String): LocalDate? {
        val ym = YEAR_MONTH.find(contractName)?.value ?: return null
        val first = runCatching { LocalDate.of(ym.substring(0, 4).toInt(), ym.substring(4, 6).toInt(), 1) }.getOrNull() ?: return null
        return first.with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.THURSDAY))
    }
}
