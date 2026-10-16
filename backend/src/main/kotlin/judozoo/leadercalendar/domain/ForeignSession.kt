package judozoo.leadercalendar.domain

import judozoo.library.previousWeekday
import java.time.LocalDate

object ForeignSession {
    fun pairedWith(on: LocalDate): LocalDate = on.previousWeekday()
}
