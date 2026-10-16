package judozoo.leadertimeline.domain

import judozoo.market.domain.Region
import java.time.LocalTime

enum class CaptureWindow(private val start: LocalTime, private val end: LocalTime, private val gaps: List<ClosedOpen>) {
    KR(LocalTime.of(8, 1), LocalTime.of(20, 0), listOf(ClosedOpen(LocalTime.of(8, 50), LocalTime.of(9, 0)), ClosedOpen(LocalTime.of(15, 30), LocalTime.of(15, 40)))),
    US(LocalTime.of(4, 0), LocalTime.of(20, 0), emptyList()),
    ;

    data class ClosedOpen(val from: LocalTime, val until: LocalTime) {
        operator fun contains(at: LocalTime) = !at.isBefore(from) && at.isBefore(until)
    }

    fun captures(at: LocalTime): Boolean = !at.isBefore(start) && !at.isAfter(end) && gaps.none { at in it }

    companion object {
        fun of(region: Region): CaptureWindow = valueOf(region.name)
    }
}
