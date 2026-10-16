package judozoo.overseasleadingstock.presentation

import judozoo.library.web.ApiResponse
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.overseasleadingstock.domain.FilterResult
import judozoo.overseasleadingstock.domain.OverseasStockRank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/overseas-leading-stocks")
class OverseasLeadingStockController(private val service: OverseasLeadingStockService) {
    data class RankItem(
        val rank: Int,
        val exchange: String,
        val symbol: String,
        val name: String,
        val ename: String,
        val price: Double,
        val diff: Double,
        val rate: Double,
        val tradingValue: Double,
    ) {
        companion object {
            fun of(r: OverseasStockRank) = RankItem(r.rank, r.exchange, r.symbol, r.name, r.ename, r.price, r.diff, r.rate, r.tradingValue)
        }
    }

    data class StockDetail(
        val exchange: String,
        val symbol: String,
        val name: String,
        val ename: String,
        val rank: Int,
        val price: Double,
        val diff: Double,
        val rate: Double,
        val tradingValue: Double,
        val marketCap: Long?,
        val filterResults: List<FilterResult>,
    )

    data class MinuteCandleItem(
        val time: LocalDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val tradingValue: Double,
    )

    data class DailyCandleItem(val date: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

    @GetMapping("/candidates")
    fun candidates(@RequestParam(required = false) minChangeRate: Int?): ApiResponse<List<RankItem>> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble() ?: DEFAULT_MIN_CHANGE_RATE
        return ApiResponse.ok(service.candidates(rate).map(RankItem::of))
    }

    @GetMapping("/leaders")
    fun leaders(): ApiResponse<List<RankItem>> = ApiResponse.ok(service.leaders(LEADERS_COUNT).map(RankItem::of))

    @GetMapping("/{exchange}/{symbol}")
    fun detail(@PathVariable exchange: String, @PathVariable symbol: String): ApiResponse<StockDetail> {
        val (stock, marketCap, filters) = service.evaluate(exchange.uppercase(), symbol.uppercase())
        return ApiResponse.ok(
            StockDetail(
                stock.exchange, stock.symbol, stock.name, stock.ename, stock.rank, stock.price, stock.diff, stock.rate,
                stock.tradingValue, marketCap, filters,
            ),
        )
    }

    @GetMapping("/{exchange}/{symbol}/minute-candles")
    fun minuteCandles(@PathVariable exchange: String, @PathVariable symbol: String) = ApiResponse.ok(
        service.minuteCandles(exchange.uppercase(), symbol.uppercase())
            .map { MinuteCandleItem(it.dateTime, it.open, it.high, it.low, it.close, it.volume, it.tradingValue) },
    )

    @GetMapping("/{exchange}/{symbol}/daily-candles")
    fun dailyCandles(@PathVariable exchange: String, @PathVariable symbol: String) = ApiResponse.ok(
        service.dailyCandles(exchange.uppercase(), symbol.uppercase())
            .map { DailyCandleItem(it.date.toString(), it.open, it.high, it.low, it.close, it.volume) },
    )

    companion object {
        private const val MIN_CHANGE_RATE = -12
        private const val MAX_CHANGE_RATE = 7
        private const val DEFAULT_MIN_CHANGE_RATE = 7.0

        const val LEADERS_COUNT = 5
    }
}
