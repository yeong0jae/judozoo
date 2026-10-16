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

/**
 * 주도주 타임라인 API — 공개다.
 *
 * 하루는 약 700분 × 5종목이라, 종목 이름·코드는 사전으로 한 번만 싣고 분마다 번호만 보낸다.
 */
@RestController
@RequestMapping("/api/leader-timeline")
class LeaderTimelineController(private val service: LeaderTimelineService) {

    data class TimelineStock(val exchange: String?, val code: String, /** 그날 마지막으로 찍힌 이름 */ val name: String)

    /** 한 분. [stocks]는 종목 사전의 번호(순위 순) — 비어 있으면 그 분엔 주도주가 없었다. [at]은 현지 HH:MM. */
    data class TimelineTick(val at: String, val stocks: List<Int>, val rates: List<Double>, val values: List<Double>)

    /** [lastTakenAt]은 그날 마지막으로 찍은 실제 시각(KST) — 화면의 "47초 전". */
    data class Timeline(val stocks: List<TimelineStock>, val ticks: List<TimelineTick>, val lastTakenAt: LocalDateTime?)

    /**
     * `market=kr&date=2026-09-23`. 날짜·시각은 그 시장의 현지 기준 — 해외는 뉴욕.
     * `since=10:59`면 그 **뒤** 분만(장중에 새 분만 받아 붙일 때).
     */
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
