package judozoo.closingbet.domain

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
    }
}
