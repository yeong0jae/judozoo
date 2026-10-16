package judozoo.closingbet.domain

import java.time.LocalDate

class OpenDays(days: Iterable<LocalDate>) {
    private val days = days.toSortedSet()

    fun isOpen(on: LocalDate): Boolean = on in days

    fun previous(on: LocalDate): LocalDate? = days.headSet(on).lastOrNull()

    fun next(on: LocalDate): LocalDate? = days.tailSet(on.plusDays(1)).firstOrNull()
}
