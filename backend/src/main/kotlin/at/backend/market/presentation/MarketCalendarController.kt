package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketCalendarService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 시장 휴장 상태 — 배너용. region KR(국내)/US(해외). 토스 장 운영 정보 기반. */
@RestController
class MarketCalendarController(
    private val service: MarketCalendarService,
) {

    @GetMapping("/api/market/calendar/status")
    fun status(@RequestParam region: MarketCalendarService.Region): ApiResponse<CalendarStatus> =
        ApiResponse.ok(CalendarStatus(isHoliday = service.isHoliday(region)))

    data class CalendarStatus(val isHoliday: Boolean)
}
