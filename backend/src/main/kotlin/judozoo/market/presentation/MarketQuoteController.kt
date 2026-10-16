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

@RestController
@RequestMapping("/api/market")
class MarketQuoteController(
    private val quotes: MarketQuotes,
    private val candles: MarketCandles,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) {
    data class CalendarStatus(val isHoliday: Boolean, val previousOpenDay: LocalDate?)

    data class IndexItem(val currentValue: Double, val changeRate: Double)

    data class CandleItem(val date: String, val time: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

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
        private val SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")

        private fun yahooItem(b: YahooBar) = CandleItem(b.date, b.time, b.open, b.high, b.low, b.close, b.volume)
        private fun barItem(b: FuturesBar) = CandleItem(b.date, b.time, b.open, b.high, b.low, b.close, b.volume)
        private fun tossItem(c: TossCandle) =
            CandleItem(c.timestamp.toLocalDate().toString(), c.timestamp.toLocalTime().format(SECONDS), c.open, c.high, c.low, c.close, c.volume)
    }
}
