package judozoo.leadertimeline.presentation

import jakarta.validation.constraints.Pattern
import judozoo.leadertimeline.application.LeaderTimelineService
import judozoo.library.web.ApiResponse
import judozoo.market.domain.Region
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/leader-timeline")
class LeaderTimelineController(private val service: LeaderTimelineService) {
    data class TimelineStock(val exchange: String?, val code: String, val name: String)

    data class TimelineTick(val at: String, val stocks: List<Int>, val rates: List<Double>, val values: List<Double>)

    data class Timeline(val stocks: List<TimelineStock>, val ticks: List<TimelineTick>, val lastTakenAt: LocalDateTime?)

    @GetMapping
    fun day(
        @RequestParam @Pattern(regexp = "kr|us") market: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(required = false) @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") since: String?,
    ): ApiResponse<Timeline> {
        val ticks = service.findDay(Region.valueOf(market.uppercase()), date, since?.let { date.atTime(LocalTime.parse(it)) })
        val index = LinkedHashMap<Pair<String?, String>, Int>()
        val names = HashMap<Int, String>()
        val items = ticks.map { t ->
            val ids = t.stocks.map { s ->
                val id = index.getOrPut(s.exchange to s.code) { index.size }
                names[id] = s.name
                id
            }
            TimelineTick(t.at.format(HH_MM), ids, t.stocks.map { it.changeRate }, t.stocks.map { it.tradingValue.toDouble() })
        }
        val stocks = index.map { (key, id) -> TimelineStock(key.first, key.second, names.getValue(id)) }
        return ApiResponse.ok(Timeline(stocks, items, ticks.lastOrNull()?.takenAt))
    }

    companion object {
        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
