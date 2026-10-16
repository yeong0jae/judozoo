package judozoo.insight.domain

import judozoo.market.domain.Region
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

enum class Schedule(
    private val reset: LocalTime,
    private val windowStart: LocalTime,
    private val windowEnd: LocalTime,
    private val slots: List<LocalTime>,
    private val close: LocalTime,
) {
    KR(
        LocalTime.of(8, 0), LocalTime.of(8, 30), LocalTime.of(20, 0),
        listOf("08:30", "09:30", "11:00", "14:00", "16:00", "18:00", "20:00").map(LocalTime::parse), LocalTime.of(15, 30),
    ),

    US(
        LocalTime.of(4, 0), LocalTime.of(8, 0), LocalTime.of(18, 0),
        listOf("08:00", "09:00", "10:00", "11:00", "13:00", "16:30", "18:00").map(LocalTime::parse), LocalTime.of(16, 0),
    ),
    ;

    fun inWindow(at: LocalTime): Boolean = at.truncatedTo(ChronoUnit.MINUTES).let { !it.isBefore(windowStart) && !it.isAfter(windowEnd) }

    fun isSlot(at: LocalTime): Boolean = slots.any { slot ->
        val elapsed = Duration.between(slot, at.truncatedTo(ChronoUnit.MINUTES))
        !elapsed.isNegative && elapsed < SLOT_GRACE
    }

    fun articlesSince(previousOpenDay: LocalDate): LocalDateTime = previousOpenDay.atTime(close)

    fun sessionDay(localNow: LocalDateTime, todayOpen: Boolean, previousOpenDay: LocalDate): LocalDate =
        if (!todayOpen || localNow.toLocalTime().isBefore(reset)) previousOpenDay else localNow.toLocalDate()

    data class Pick(val code: String, val trigger: Trigger)

    fun pick(leaders: List<String>, history: Map<String, Attempts>, now: LocalDateTime, candidates: List<String> = emptyList()): List<Pick> {
        if (!inWindow(now.toLocalTime())) return emptyList()
        val slot = isSlot(now.toLocalTime())
        val lead = leaders.toSet()
        return (leaders + candidates.filterNot { it in lead }).mapNotNull { code ->
            val done = history[code] ?: Attempts()
            when {
                done.isEmpty() -> Pick(code, Trigger.ENTRY)
                done.needsRetry(now) -> Pick(code, Trigger.RETRY)
                slot && !done.madeWithin(now, RECENT) && (code in lead || !done.explained) -> Pick(code, Trigger.SCHEDULED)
                else -> null
            }
        }
    }

    companion object {
        val SLOT_GRACE: Duration = Duration.ofMinutes(5)

        val RECENT: Duration = Duration.ofMinutes(30)

        fun of(region: Region): Schedule = valueOf(region.name)
    }
}
