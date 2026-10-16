package judozoo.market.presentation

import judozoo.library.web.ApiResponse
import judozoo.market.application.MarketCalendar
import judozoo.market.application.MarketCandles
import judozoo.market.application.MarketQuotes
import judozoo.market.domain.Region
import judozoo.platform.kis.KisFutures.FuturesBar
import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.platform.yahoo.YahooClient.YahooBar
import judozoo.stock.domain.Market
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 시황 API 중 시세·캔들·휴장. 투자자 수급은 [MarketInvestorController].
 *
 * 리터럴 경로(`calendar`·`macro`·`nasdaq`·`futures/night`·`futures/nasdaq`)와 `{market}` 경로가 겹친다 —
 * Spring은 리터럴을 먼저 고르므로 선언 순서는 상관없다(Python FastAPI는 선언 순서로 골랐다).
 */
@RestController
@RequestMapping("/api/market")
class MarketQuoteController(
    private val quotes: MarketQuotes,
    private val candles: MarketCandles,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    /** [previousOpenDay]는 오늘 전의 마지막 개장일 — 국내만(개장일 목록이 KIS 국내 것뿐이다). 모르면 null. */
    data class CalendarStatus(val isHoliday: Boolean, val previousOpenDay: LocalDate?)

    data class IndexItem(val currentValue: Double, val changeRate: Double)

    /** [date]는 yyyy-MM-dd(KST), [time]은 HH:mm:ss — 1d는 항상 00:00:00. */
    data class CandleItem(val date: String, val time: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

    /** 시장 휴장 상태 — 배너용. */
    @GetMapping("/calendar/status")
    fun calendarStatus(@RequestParam region: Region): ApiResponse<CalendarStatus> {
        val previous = if (region == Region.KR) calendar.previousOpenDay(region.today(clock.instant())) else null
        return ApiResponse.ok(CalendarStatus(calendar.isHoliday(region), previous))
    }

    @GetMapping("/kospi")
    fun kospi() = ApiResponse.ok(quotes.index(Market.KOSPI).let { IndexItem(it.currentValue, it.changeRate) })

    @GetMapping("/kosdaq")
    fun kosdaq() = ApiResponse.ok(quotes.index(Market.KOSDAQ).let { IndexItem(it.currentValue, it.changeRate) })

    @GetMapping("/macro/quotes")
    fun macroQuotes() = ApiResponse.ok(quotes.macroQuotes())

    @GetMapping("/macro/candles")
    fun macroCandles(@RequestParam target: String, @RequestParam interval: String) = ApiResponse.ok(candles.macro(target, interval).map(::yahooItem))

    @GetMapping("/nasdaq/quote")
    fun nasdaqQuote() = ApiResponse.ok(quotes.nasdaqQuote())

    @GetMapping("/nasdaq/candles")
    fun nasdaqCandles(@RequestParam interval: String) = ApiResponse.ok(candles.nasdaq(interval).map(::yahooItem))

    @GetMapping("/futures/night/quote")
    fun nightQuote() = ApiResponse.ok(quotes.nightFuturesQuote())

    @GetMapping("/futures/night/candles")
    fun nightCandles(@RequestParam interval: String, @RequestParam(defaultValue = "90") count: Int) = ApiResponse.ok(
        when (interval) {
            "1d" -> candles.nightDaily(count)
            "1m" -> candles.nightMinute()
            else -> emptyList()
        }.map(::barItem),
    )

    @GetMapping("/futures/nasdaq/quote")
    fun nasdaqFuturesQuote() = ApiResponse.ok(quotes.nasdaqFuturesQuote())

    @GetMapping("/futures/nasdaq/candles")
    fun nasdaqFuturesCandles(@RequestParam interval: String) = ApiResponse.ok(candles.nasdaqFutures(interval).map(::yahooItem))

    @GetMapping("/futures/{market}/quote")
    fun futuresQuote(@PathVariable market: Market) = ApiResponse.ok(quotes.futuresQuote(market))

    @GetMapping("/futures/{market}/candles")
    fun futuresCandles(@PathVariable market: Market, @RequestParam interval: String, @RequestParam(defaultValue = "90") count: Int) = ApiResponse.ok(
        when (interval) {
            "1d" -> candles.futuresDaily(market, count)
            "1m" -> candles.futuresMinute(market)
            else -> emptyList()
        }.map(::barItem),
    )

    @GetMapping("/{market}/candles")
    fun marketCandles(@PathVariable market: Market, @RequestParam interval: String, @RequestParam(defaultValue = "90") count: Int) = ApiResponse.ok(
        when (interval) {
            "1d" -> candles.indexDaily(market, count)
            "1m" -> candles.indexMinute(market)
            else -> emptyList()
        }.map(::tossItem),
    )

    companion object {
        /** Python `time.isoformat()`과 같게 초까지 적는다 — `LocalTime.toString()`은 0초를 뺀다. */
        private val SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")

        private fun yahooItem(b: YahooBar) = CandleItem(b.date, b.time, b.open, b.high, b.low, b.close, b.volume)
        private fun barItem(b: FuturesBar) = CandleItem(b.date, b.time, b.open, b.high, b.low, b.close, b.volume)
        private fun tossItem(c: TossCandle) =
            CandleItem(c.timestamp.toLocalDate().toString(), c.timestamp.toLocalTime().format(SECONDS), c.open, c.high, c.low, c.close, c.volume)
    }
}
