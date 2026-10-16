package judozoo.closingbet.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 지금 화면이 다루는 판들. 한 "판"은 베팅한 거래일로 부르고, 다음 개장일 아침에 판다.
 *
 * - [betting] — 베팅을 받거나(베팅) 체결을 기다리는(대기) 판. 그 밖에는 null
 * - [review] — 복기·결과 발표에 보여 줄 판(가장 최근에 팔았거나 지금 파는 판)
 * - [nextAt] — 다음 칸이 시작하는 시각(카운트다운)
 */
data class Phase(val moment: Moment, val betting: LocalDate?, val review: LocalDate?, val nextAt: LocalDateTime?) {
    val acceptsBets: Boolean get() = moment == Moment.BET

    companion object {
        val BET_OPEN: LocalTime = LocalTime.of(15, 0)
        val BET_CLOSE: LocalTime = LocalTime.of(20, 0)
        val RESULT_START: LocalTime = LocalTime.of(8, 0)
        val RESULT_END: LocalTime = LocalTime.of(9, 5)

        /** [now]는 KST 벽시계다. */
        fun at(now: LocalDateTime, days: OpenDays): Phase {
            val today = now.toLocalDate()
            val t = now.toLocalTime()
            val previous = days.previous(today)
            if (!days.isOpen(today) || t.isBefore(RESULT_START)) {
                // 직전 개장일 밤에 체결된 판이 아직 안 팔렸다. 쉬는 날도 다음 개장일 아침까지 그 판을 기다린다
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
