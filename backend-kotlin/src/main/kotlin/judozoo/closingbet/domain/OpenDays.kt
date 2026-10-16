package judozoo.closingbet.domain

import java.time.LocalDate

/** 개장일 집합. 이 범위 밖의 날짜는 묻지 않는다 — 지어내지 않는다. */
class OpenDays(days: Iterable<LocalDate>) {
    private val days = days.toSortedSet()

    fun isOpen(on: LocalDate): Boolean = on in days

    fun previous(on: LocalDate): LocalDate? = days.headSet(on).lastOrNull()

    fun next(on: LocalDate): LocalDate? = days.tailSet(on.plusDays(1)).firstOrNull()
}
