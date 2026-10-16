package judozoo.closingbet.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Phase(val moment: Moment, val betting: LocalDate?, val review: LocalDate?, val nextAt: LocalDateTime?) {
    val acceptsBets: Boolean get() = moment == Moment.BET

    companion object {
        val BET_OPEN: LocalTime = LocalTime.of(15, 0)
        val BET_CLOSE: LocalTime = LocalTime.of(20, 0)
        val RESULT_START: LocalTime = LocalTime.of(8, 0)
        val RESULT_END: LocalTime = LocalTime.of(9, 5)

        fun at(now: LocalDateTime, days: OpenDays): Phase {
            val today = now.toLocalDate()
            val t = now.toLocalTime()
            val previous = days.previous(today)
            if (!days.isOpen(today) || t.isBefore(RESULT_START)) {
                val upcoming = if (days.isOpen(today)) today else days.next(today)
                return Phase(Moment.NIGHT, previous, previous?.let(days::previous), upcoming?.atTime(RESULT_START))
            }
            return when {
                t.isBefore(RESULT_END) -> Phase(Moment.RESULT, null, previous, today.atTime(RESULT_END))
                t.isBefore(BET_OPEN) -> Phase(Moment.REVIEW, null, previous, today.atTime(BET_OPEN))
                t.isBefore(BET_CLOSE) -> Phase(Moment.BET, today, previous, today.atTime(BET_CLOSE))
                else -> Phase(Moment.NIGHT, today, previous, days.next(today)?.atTime(RESULT_START))
            }
        }
    }
}
