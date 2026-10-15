package judozoo.market.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 시장 지역. 쿼리 파라미터로 그대로 바인딩되도록 이름이 곧 코드다. */
enum class Region(val zone: ZoneId) {
    KR(ZoneId.of("Asia/Seoul")),
    US(ZoneId.of("America/New_York")),
    ;

    /** '오늘'은 지역의 **현지 날짜**다 — KR은 KST, US는 미 동부시각 기준. */
    fun today(at: Instant): LocalDate = at.atZone(zone).toLocalDate()
}
