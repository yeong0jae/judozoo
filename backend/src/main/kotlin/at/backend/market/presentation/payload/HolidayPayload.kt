package at.backend.market.presentation.payload

import java.time.Instant

/**
 * `/topic/market` HOLIDAY 페이로드 — 휴장 토글.
 */
data class HolidayPayload(
    val isHoliday: Boolean,
    val ts: Instant,
) {
    val type: String = "HOLIDAY"
}
