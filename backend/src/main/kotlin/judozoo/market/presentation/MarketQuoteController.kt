package judozoo.market.presentation

import judozoo.library.web.ApiResponse
import judozoo.market.application.MarketCalendar
import judozoo.market.application.MarketCandles
import judozoo.market.application.MarketQuotes
import judozoo.market.domain.CandleInterval
import judozoo.market.domain.Indicator
import judozoo.market.domain.MarketCandle
import judozoo.market.domain.Region
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

    data class CandleItem(val date: String, val time: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double) {
        companion object {
            fun of(c: MarketCandle) = CandleItem(c.at.toLocalDate().toString(), c.at.toLocalTime().format(SECONDS), c.open, c.high, c.low, c.close, c.volume)
        }
    }

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
    fun macroCandles(@RequestParam target: String, @RequestParam interval: String) =
        ApiResponse.ok(candlesOf(interval) { Indicator.macro(target)?.let { m -> candles.indicator(m, it) }.orEmpty() })

    @GetMapping("/nasdaq/quote")
    fun nasdaqQuote() = ApiResponse.ok(quotes.nasdaqQuote())

    @GetMapping("/nasdaq/candles")
    fun nasdaqCandles(@RequestParam interval: String) = ApiResponse.ok(candlesOf(interval) { candles.indicator(Indicator.NASDAQ, it) })

    @GetMapping("/futures/night/quote")
    fun nightQuote() = ApiResponse.ok(quotes.nightFuturesQuote())

    @GetMapping("/futures/night/candles")
    fun nightCandles(@RequestParam interval: String, @RequestParam(defaultValue = "90") count: Int) =
        ApiResponse.ok(candlesOf(interval) { candles.nightFutures(it, count) })

    @GetMapping("/futures/nasdaq/quote")
    fun nasdaqFuturesQuote() = ApiResponse.ok(quotes.nasdaqFuturesQuote())

    @GetMapping("/futures/nasdaq/candles")
    fun nasdaqFuturesCandles(@RequestParam interval: String) = ApiResponse.ok(candlesOf(interval) { candles.indicator(Indicator.NASDAQ_FUTURES, it) })

    @GetMapping("/futures/{market}/quote")
    fun futuresQuote(@PathVariable market: Market) = ApiResponse.ok(quotes.futuresQuote(market))

    @GetMapping("/futures/{market}/candles")
    fun futuresCandles(@PathVariable market: Market, @RequestParam interval: String, @RequestParam(defaultValue = "90") count: Int) =
        ApiResponse.ok(candlesOf(interval) { candles.futures(market, it, count) })

    @GetMapping("/{market}/candles")
    fun marketCandles(@PathVariable market: Market, @RequestParam interval: String, @RequestParam(defaultValue = "90") count: Int) =
        ApiResponse.ok(candlesOf(interval) { candles.index(market, it, count) })

    private fun candlesOf(interval: String, load: (CandleInterval) -> List<MarketCandle>): List<CandleItem> =
        CandleInterval.of(interval)?.let(load).orEmpty().map(CandleItem::of)

    companion object {
        private val SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
