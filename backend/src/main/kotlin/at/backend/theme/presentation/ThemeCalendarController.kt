package at.backend.theme.presentation

import at.backend.library.web.ApiResponse
import at.backend.theme.application.ThemeCalendarService
import at.backend.theme.presentation.response.ThemeCalendarResponse
import at.backend.theme.presentation.response.ThemeDayItem
import at.backend.theme.presentation.response.ThemeItem
import at.backend.theme.presentation.response.ThemeStockItem
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/themes")
class ThemeCalendarController(
    private val service: ThemeCalendarService,
) {

    /** 기간 내 일자별 상위 테마. 캘린더 화면이 한 달 범위로 조회한다. */
    @GetMapping("/calendar")
    fun calendar(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ApiResponse<ThemeCalendarResponse> {
        val days = service.getCalendar(from, to)
            .groupBy { it.record.date }
            .map { (date, list) ->
                ThemeDayItem(
                    date = date,
                    themes = list.sortedBy { it.record.rank }.map { tw ->
                        ThemeItem(
                            rank = tw.record.rank,
                            name = tw.record.themeName,
                            tradingValue = tw.record.tradingValue,
                            stocks = tw.stocks.map {
                                ThemeStockItem(it.stockCode, it.stockName, it.tradingValue)
                            },
                        )
                    },
                )
            }
            .sortedBy { it.date }
        return ApiResponse.ok(ThemeCalendarResponse(days))
    }

    /** 오늘치 테마를 즉시 캡처 (스케줄 대기 없이 시드/테스트용). 반환=저장 건수. */
    @PostMapping("/capture")
    fun capture(): ApiResponse<Int> = ApiResponse.ok(service.capture())
}
