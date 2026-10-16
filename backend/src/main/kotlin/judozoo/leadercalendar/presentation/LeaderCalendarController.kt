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
import java.time.YearMonth

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

    data class LeaderDayItem(val date: LocalDate, val closed: Boolean, val live: Boolean, val stocks: List<LeaderStockItem>)

    data class LeaderCalendar(val domestic: List<LeaderDayItem>, val overseas: List<LeaderDayItem>)

    @GetMapping
    fun month(@RequestParam @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") month: String): ApiResponse<LeaderCalendar> {
        val yearMonth = YearMonth.parse(month)
        val (domestic, overseas) = service.findMonth(yearMonth.year, yearMonth.monthValue)
        return ApiResponse.ok(LeaderCalendar(items(domestic), items(overseas)))
    }

    private fun items(days: List<RecordedDay>) = days.map { d ->
        LeaderDayItem(
            d.tradeDate, d.closed, d.live,
            d.stocks.map { LeaderStockItem(it.rank, it.exchange, it.code, it.name, it.price.toDouble(), it.changeRate, it.tradingValue.toDouble()) },
        )
    }
}
