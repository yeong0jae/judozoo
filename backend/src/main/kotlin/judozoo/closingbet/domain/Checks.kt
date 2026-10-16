package judozoo.closingbet.domain

import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.MinuteCandle
import java.time.LocalDate
import java.time.LocalTime

/**
 * 종베 체크 여섯 가지. 순매수는 통합(KRX+NXT), 마감 부근 수급은 그 종목 시장의 마감 + 애프터 외인 + 기관,
 * 구간 신고가는 직전 60거래일 최고가에서 기준(−5%) 안, 애프터 버팀은 20:00 종가가 15:30 정규장 종가 이상.
 */
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

    /** DB·화면에 나가는 모양 — Python과 같은 snake_case 키. */
    fun toMap(): Map<String, Boolean> = mapOf(
        "near_high" to nearHigh, "foreign" to foreign, "institution" to institution,
        "market_late" to marketLate, "recent_high" to recentHigh, "after_hold" to afterHold,
    )

    companion object {
        /** 고가 대비 % */
        const val NEAR_HIGH_RATE = -1.5

        /** 최근 고점 — 주도주 조건 "최근 고가 대비 현재가"와 같은 60봉 */
        const val RECENT_HIGH_DAYS = 60

        val REGULAR_CLOSE: LocalTime = LocalTime.of(15, 30)

        /** [recentHighFloor]는 주도주 조건의 기준(−5%)을 그대로 받는다. */
        fun of(
            price: Long, high: Long, foreignNet: Long, institutionNet: Long, marketLateNet: Long,
            recentHighGap: Double?, recentHighFloor: Double, regularClose: Long?,
        ) = Checks(
            // 나눗셈 대신 곱셈 — 정확히 −1.5%인 경계가 부동소수 오차로 떨어지지 않게
            nearHigh = high > 0 && price * 100.0 >= high * (100 + NEAR_HIGH_RATE),
            foreign = foreignNet > 0,
            institution = institutionNet > 0,
            marketLate = marketLateNet > 0,
            recentHigh = recentHighGap != null && recentHighGap >= recentHighFloor,
            // 정규장이 아직 안 끝났으면(15:00~15:30) 모른다 — 통과로 세지 않는다
            afterHold = regularClose != null && price >= regularClose,
        )

        fun fromMap(map: Map<String, Boolean>) = Checks(
            map["near_high"] ?: false, map["foreign"] ?: false, map["institution"] ?: false,
            map["market_late"] ?: false, map["recent_high"] ?: false,
            // 애프터 버팀이 생기기 전(V010)에 찍힌 판에는 없다 — false로 읽는다
            map["after_hold"] ?: false,
        )

        /**
         * 최근 고점과의 거리(%) — 직전 60거래일 최고가 대비. [history]는 **오늘을 뺀** 과거 일봉.
         * 종목 상세의 주도주 조건과 같은 식이다. 두 화면이 같은 종목에 다른 말을 하면 안 된다.
         */
        fun recentHighGap(history: List<DailyCandle>, price: Long): Double? {
            val top = history.sortedByDescending { it.date }.take(RECENT_HIGH_DAYS).maxOfOrNull { it.highPrice } ?: return null
            if (top <= 0) return null
            return (price - top).toDouble() / top * 100
        }

        /**
         * 그날 정규장 종가 — 15:30 단일가까지의 마지막 체결. 정규장이 아직 안 끝났으면 null.
         * 15:30 뒤에도 20:00까지 체결이 이어져(027 0단계) 일봉 종가는 정규장 종가가 아니다. 분봉으로 따로 잡는다.
         */
        fun regularClose(bars: List<MinuteCandle>, day: LocalDate): Long? {
            val today = bars.filter { it.dateTime.toLocalDate() == day && it.volume > 0 }.sortedBy { it.dateTime }
            if (today.none { !it.dateTime.toLocalTime().isBefore(REGULAR_CLOSE) }) return null
            return today.lastOrNull { !it.dateTime.toLocalTime().isAfter(REGULAR_CLOSE) }?.closePrice
        }
    }
}
