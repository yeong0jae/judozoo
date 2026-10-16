package judozoo.overseasleadingstock.domain

import java.time.LocalDateTime
import java.time.LocalTime

object UsRegularSession {
    val OPEN: LocalTime = LocalTime.of(9, 30)

    fun openPrice(bars: List<Pair<LocalDateTime, Double>>): Double? =
        bars.filter { (at, price) -> !at.toLocalTime().isBefore(OPEN) && price > 0 }.minByOrNull { it.first }?.second
}
