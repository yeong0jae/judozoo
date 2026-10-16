package judozoo.leadingstock.presentation

import judozoo.auth.domain.CurrentUser
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.FilterResult
import judozoo.leadingstock.domain.LeadingStockCriteria
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.library.KST
import judozoo.library.web.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/leading-stocks")
class LeadingStockController(private val service: LeadingStockService, private val clock: Clock) {
    data class CandidateStockItem(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val accumulatedTradingValue: Long,
    ) {
        companion object {
            fun of(rank: Int, s: LeadingStockSnapshot) =
                CandidateStockItem(rank, s.stockCode, s.stockName, s.currentPrice, s.priceChangeRate, s.accumulatedTradingValue)
        }
    }

    data class CandidateStocks(val queriedAt: LocalDateTime, val totalCount: Int, val stocks: List<CandidateStockItem>)

    data class LimitUpItem(val stockCode: String, val stockName: String)

    data class Leaders(val leaders: List<CandidateStockItem>, val limitUps: List<LimitUpItem>)

    data class BreakoutRadarItem(
        val stockCode: String,
        val stockName: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val peakPrice: Long,
        val peakAt: LocalDateTime,
        val gapRate: Double,
        val troughPrice: Long?,
        val troughAt: LocalDateTime?,
        val supportGapRate: Double?,
        val tradingValue: Long,
    )

    data class BreakoutRadar(val queriedAt: LocalDateTime, val totalCount: Int, val stocks: List<BreakoutRadarItem>)

    data class StockDetail(
        val stockCode: String,
        val stockName: String,
        val currentPrice: Long,
        val priceChangeRate: Double,
        val relativeVolume: Double?,
        val market: String?,
        val filterResults: List<FilterResult>,
        val openingPrice: Long,
        val highPrice: Long,
        val lowPrice: Long,
        val previousClose: Long,
        val tradingValue: Long?,
    )

    data class MinuteCandleItem(
        val time: LocalDateTime,
        val open: Long,
        val high: Long,
        val low: Long,
        val close: Long,
        val volume: Long,
        val tradingValue: Long,
    )

    data class DailyCandleItem(val date: String, val open: Long, val high: Long, val low: Long, val close: Long, val volume: Long)

    @GetMapping("/candidates")
    fun candidates(@RequestParam(required = false) minChangeRate: Int?): ApiResponse<CandidateStocks> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble() ?: LeadingStockCriteria().minDailyPriceChangeRate
        val items = service.findCandidates(rate).mapIndexed { i, s -> CandidateStockItem.of(i + 1, s) }
        return ApiResponse.ok(CandidateStocks(now(), items.size, items))
    }

    @GetMapping("/leaders")
    fun leaders() = ApiResponse.ok(
        Leaders(
            service.findLeaders(LEADERS_COUNT).mapIndexed { i, s -> CandidateStockItem.of(i + 1, s) },
            service.findLimitUps().map { LimitUpItem(it.stockCode, it.stockName) },
        ),
    )

    @GetMapping("/breakout-radar")
    fun breakoutRadar(@RequestParam(defaultValue = "resistance") mode: String, user: CurrentUser?): ApiResponse<BreakoutRadar> {
        val support = mode == "support"
        fun line(item: BreakoutRadarItem) = if (support) item.supportGapRate else item.gapRate
        val near = service.breakoutRadar()
            .map { (s, peak, trough) ->
                BreakoutRadarItem(
                    s.stockCode, s.stockName, s.currentPrice, s.priceChangeRate, peak.peakPrice, peak.peakAt, peak.gapRate,
                    trough?.troughPrice, trough?.troughAt, trough?.gapRate, s.accumulatedTradingValue,
                )
            }
            .filter { item -> line(item)?.let { it <= RADAR_NEAR_RATE } ?: false }
            .sortedBy { line(it) }
        val items = if (user == null) near.take(RADAR_PREVIEW_COUNT) else near
        return ApiResponse.ok(BreakoutRadar(now(), near.size, items))
    }

    @GetMapping("/candidates/{stockCode}/minute-candles")
    fun minuteCandles(@PathVariable stockCode: String, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?) =
        ApiResponse.ok(
            service.minuteCandles(stockCode, date ?: today())
                .map { MinuteCandleItem(it.dateTime, it.openPrice, it.highPrice, it.lowPrice, it.closePrice, it.volume, it.tradingValue) },
        )

    @GetMapping("/candidates/{stockCode}/daily-candles")
    fun dailyCandles(@PathVariable stockCode: String, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?) =
        ApiResponse.ok(
            service.dailyCandles(stockCode, date ?: today())
                .map { DailyCandleItem(it.date.toString(), it.openPrice, it.highPrice, it.lowPrice, it.closePrice, it.volume) },
        )

    @GetMapping("/candidates/{stockCode}")
    fun detail(@PathVariable stockCode: String): ApiResponse<StockDetail> {
        val (s, filters, relativeVolume, market) = service.evaluate(stockCode)
        return ApiResponse.ok(
            StockDetail(
                s.stockCode, s.stockName, s.currentPrice, s.priceChangeRate, relativeVolume, market?.name, filters,
                s.openingPrice, s.highPrice, s.lowPrice, s.previousClose, s.accumulatedTradingValue.takeIf { it != 0L },
            ),
        )
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(KST))

    private fun today(): LocalDate = now().toLocalDate()

    companion object {
        private const val MIN_CHANGE_RATE = -12
        private const val MAX_CHANGE_RATE = 7

        const val LEADERS_COUNT = 5

        const val RADAR_PREVIEW_COUNT = 3

        const val RADAR_NEAR_RATE = 3.0
    }
}
