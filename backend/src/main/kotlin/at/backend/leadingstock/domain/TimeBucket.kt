package at.backend.leadingstock.domain

import java.time.LocalTime

/**
 * 신호 발생 시간대 — 정규장 경계(09:00·15:30)로 NXT 프리/애프터를 가른다.
 * 같은 종류라도 시간대마다 사후 흐름이 다르므로 통계를 이 차원으로 더 쪼갠다.
 */
enum class TimeBucket {
    PRE_NXT,   // 08:00~09:00 NXT 프리마켓
    EARLY,     // 09:00~10:00 장초반
    MID,       // 10:00~14:30 장중
    LATE,      // 14:30~15:30 막판
    POST_NXT;  // 15:30~20:00 NXT 애프터마켓

    companion object {
        private val EARLY_FROM = LocalTime.of(9, 0)
        private val MID_FROM = LocalTime.of(10, 0)
        private val LATE_FROM = LocalTime.of(14, 30)
        private val POST_FROM = LocalTime.of(15, 30)

        fun of(time: LocalTime): TimeBucket = when {
            time < EARLY_FROM -> PRE_NXT
            time < MID_FROM -> EARLY
            time < LATE_FROM -> MID
            time < POST_FROM -> LATE
            else -> POST_NXT
        }
    }
}
