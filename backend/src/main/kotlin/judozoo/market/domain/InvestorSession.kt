package judozoo.market.domain

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

enum class InvestorSession(val label: String, val start: LocalTime, val end: LocalTime) {
    PRE_MARKET("프리마켓", LocalTime.of(8, 0), LocalTime.of(9, 0)),
    MORNING("오전", LocalTime.of(9, 0), LocalTime.of(12, 0)),
    AFTERNOON("오후", LocalTime.of(12, 0), LocalTime.of(15, 0)),
    CLOSING("마감 구간", LocalTime.of(15, 0), LocalTime.of(15, 40)),
    AFTER_MARKET("애프터마켓", LocalTime.of(15, 40), LocalTime.of(20, 0)),

    FUTURES_MORNING("오전", LocalTime.of(8, 45), LocalTime.of(12, 0)),
    FUTURES_AFTERNOON("오후", LocalTime.of(12, 0), LocalTime.of(15, 0)),
    FUTURES_CLOSING("마감 구간", LocalTime.of(15, 0), LocalTime.of(15, 45)),
    ;

    operator fun contains(at: LocalDateTime): Boolean {
        val minute = at.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        return !minute.isBefore(start) && minute.isBefore(end)
    }
}
