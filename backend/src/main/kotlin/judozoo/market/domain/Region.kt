package judozoo.market.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Region(val zone: ZoneId) {
    KR(ZoneId.of("Asia/Seoul")),
    US(ZoneId.of("America/New_York")),
    ;

    fun today(at: Instant): LocalDate = at.atZone(zone).toLocalDate()
}
