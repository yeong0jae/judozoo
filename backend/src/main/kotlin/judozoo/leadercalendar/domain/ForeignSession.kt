package judozoo.leadercalendar.domain

import java.time.DayOfWeek
import java.time.LocalDate

object ForeignSession {
    fun pairedWith(on: LocalDate): LocalDate {
        var day = on.minusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        return day
    }
}
