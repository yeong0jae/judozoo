package judozoo.closingbet.domain

import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.MinuteCandle
import java.time.LocalDate
import java.time.LocalTime

data class Checks(
    val nearHigh: Boolean,
    val foreign: Boolean,
    val institution: Boolean,
    val marketLate: Boolean,
    val recentHigh: Boolean,
    val afterHold: Boolean,
) {
    val passed: Int get() = listOf(nearHigh, foreign, institution, marketLate, recentHigh, afterHold).count { it }

    val grade: Grade get() = when (passed) {
        6 -> Grade.S
        5 -> Grade.A
        4 -> Grade.B
        else -> Grade.C
    }

    fun toMap(): Map<String, Boolean> = mapOf(
        "near_high" to nearHigh, "foreign" to foreign, "institution" to institution,
        "market_late" to marketLate, "recent_high" to recentHigh, "after_hold" to afterHold,
    )

    companion object {
        const val NEAR_HIGH_RATE = -1.5

        const val RECENT_HIGH_DAYS = 60

        val REGULAR_CLOSE: LocalTime = LocalTime.of(15, 30)

        fun of(
            price: Long, high: Long, foreignNet: Long, institutionNet: Long, marketLateNet: Long,
            recentHighGap: Double?, recentHighFloor: Double, regularClose: Long?,
        ) = Checks(
            nearHigh = high > 0 && price * 100.0 >= high * (100 + NEAR_HIGH_RATE),
            foreign = foreignNet > 0,
            institution = institutionNet > 0,
            marketLate = marketLateNet > 0,
            recentHigh = recentHighGap != null && recentHighGap >= recentHighFloor,
            afterHold = regularClose != null && price >= regularClose,
        )

        fun fromMap(map: Map<String, Boolean>) = Checks(
            map["near_high"] ?: false, map["foreign"] ?: false, map["institution"] ?: false,
            map["market_late"] ?: false, map["recent_high"] ?: false,
            map["after_hold"] ?: false,
        )

        fun recentHighGap(history: List<DailyCandle>, price: Long): Double? {
            val top = history.sortedByDescending { it.date }.take(RECENT_HIGH_DAYS).maxOfOrNull { it.highPrice } ?: return null
            if (top <= 0) return null
            return (price - top).toDouble() / top * 100
        }

        fun regularClose(bars: List<MinuteCandle>, day: LocalDate): Long? {
            val today = bars.filter { it.dateTime.toLocalDate() == day && it.volume > 0 }.sortedBy { it.dateTime }
            if (today.none { !it.dateTime.toLocalTime().isBefore(REGULAR_CLOSE) }) return null
            return today.lastOrNull { !it.dateTime.toLocalTime().isAfter(REGULAR_CLOSE) }?.closePrice
        }
    }
}
