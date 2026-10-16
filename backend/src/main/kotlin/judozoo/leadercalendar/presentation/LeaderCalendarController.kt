package judozoo.leadercalendar.presentation

import jakarta.validation.constraints.Pattern
import judozoo.leadercalendar.application.LeaderCalendarService
import judozoo.leadercalendar.application.LeaderCalendarService.RecordedDay
import judozoo.library.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 주도주 캘린더 API — 공개다. */
@RestController
@RequestMapping("/api/leader-calendar")
class LeaderCalendarController(private val service: LeaderCalendarService) {

    data class LeaderStockItem(
        val rank: Int,
        val exchange: String?,
        val code: String,
        val name: String,
        val price: Double,
        val changeRate: Double,
        val tradingValue: Double,
    )

    /**
     * [closed]면 휴장. 아니고 [stocks]가 비어 있으면 그날은 주도주가 없었다. 날 자체가 없으면 기록이 없다.
     * [live]면 마감 기록 전의 오늘이라 순위가 아직 바뀐다.
     */
    data class LeaderDayItem(val date: LocalDate, val closed: Boolean, val live: Boolean, val stocks: List<LeaderStockItem>)

    /** 국내·해외를 따로 준다. 날짜는 각 시장의 현지 거래일 — 짝짓기는 화면이 한다. */
    data class LeaderCalendar(val domestic: List<LeaderDayItem>, val overseas: List<LeaderDayItem>)

    /** `month=2026-09`. 해외는 그달 1일 직전 평일부터 온다. */
    @GetMapping
    fun month(@RequestParam @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") month: String): ApiResponse<LeaderCalendar> {
        val (year, mon) = month.split("-").map(String::toInt)
        val (domestic, overseas) = service.findMonth(year, mon)
        return ApiResponse.ok(LeaderCalendar(items(domestic), items(overseas)))
    }

    private fun items(days: List<RecordedDay>) = days.map { d ->
        LeaderDayItem(
            d.tradeDate, d.closed, d.live,
            d.stocks.map { LeaderStockItem(it.rank, it.exchange, it.code, it.name, it.price.toDouble(), it.changeRate, it.tradingValue.toDouble()) },
        )
    }
}
