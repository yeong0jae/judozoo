package judozoo.leadercalendar.domain

import java.time.DayOfWeek
import java.time.LocalDate

/** 국내 날짜와 해외장을 짝짓는 규칙. */
object ForeignSession {
    /**
     * [on] 직전 평일. 국내 날짜 D의 칸에는 이 날짜의 해외장이 붙는다 — 월요일이면 금요일 장.
     * 휴장은 따지지 않는다. 그날 해외장이 쉬었으면 기록이 없을 뿐이다.
     */
    fun pairedWith(on: LocalDate): LocalDate {
        var day = on.minusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        return day
    }
}
