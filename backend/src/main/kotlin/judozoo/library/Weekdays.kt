package judozoo.library

import java.time.DayOfWeek
import java.time.LocalDate

val LocalDate.isWeekend: Boolean get() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

fun LocalDate.previousWeekday(): LocalDate = generateSequence(minusDays(1)) { it.minusDays(1) }.first { !it.isWeekend }
