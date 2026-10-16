package judozoo.market.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class Indicator(val symbol: String, val hours: QuoteHours, private val session: Session, val keepsSessions: Boolean = true) {
    NASDAQ("^IXIC", QuoteHours.US_CASH, Session.Cash(LocalTime.of(9, 30), LocalTime.of(15, 59), graceMinutes = 0)),
    NASDAQ_FUTURES("NQ=F", QuoteHours.GLOBAL, Session.Cash(LocalTime.of(9, 30), LocalTime.of(15, 59), graceMinutes = 30), keepsSessions = false),
    USD_KRW("KRW=X", QuoteHours.GLOBAL, Session.Rolling(LocalTime.of(17, 0), closeMarginMinutes = 30)),
    WTI("CL=F", QuoteHours.GLOBAL, Session.Rolling(LocalTime.of(18, 0), closeMarginMinutes = 90)),
    VIX("^VIX", QuoteHours.US_CASH, Session.Cash(LocalTime.of(9, 30), LocalTime.of(15, 59), graceMinutes = 30)),
    US10Y("^TNX", QuoteHours.US_CASH, Session.Cash(LocalTime.of(8, 20), LocalTime.of(15, 0), graceMinutes = 30)),
    ;

    sealed interface Session {
        fun day(ny: LocalDateTime): LocalDate

        fun window(day: LocalDate): ClosedRange<LocalDateTime>

        data class Cash(val open: LocalTime, val close: LocalTime, val graceMinutes: Long) : Session {
            override fun day(ny: LocalDateTime): LocalDate = ny.toLocalDate()

            override fun window(day: LocalDate) = day.atTime(open).plusMinutes(graceMinutes)..day.atTime(close).minusMinutes(graceMinutes)
        }

        data class Rolling(val rollover: LocalTime, val closeMarginMinutes: Long) : Session {
            override fun day(ny: LocalDateTime): LocalDate = if (ny.toLocalTime().isBefore(rollover)) ny.toLocalDate() else ny.toLocalDate().plusDays(1)

            override fun window(day: LocalDate) = day.minusDays(1).atTime(rollover).plusMinutes(30)..day.atTime(rollover).minusMinutes(closeMarginMinutes)
        }
    }

    fun sessionDay(ny: LocalDateTime): LocalDate = session.day(ny)

    fun completes(day: LocalDate, nyTimes: List<LocalDateTime>): Boolean {
        if (nyTimes.isEmpty()) return false
        val window = session.window(day)
        return !nyTimes.min().isAfter(window.start) && !nyTimes.max().isBefore(window.endInclusive)
    }

    companion object {
        val MACRO: List<Indicator> = listOf(USD_KRW, WTI, VIX, US10Y)

        fun macro(name: String): Indicator? = MACRO.firstOrNull { it.name == name }
    }
}
