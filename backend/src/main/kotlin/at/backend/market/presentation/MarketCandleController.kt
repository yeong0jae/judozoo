package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketCandleService
import at.backend.platform.toss.client.TossMarketIndicatorClient.TossCandle
import at.backend.stock.domain.Market
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 시장 지수 캔들(OHLCV) — 토스 Market Indicators. 종가 베팅 지수 상세 차트용. */
@RestController
class MarketCandleController(
    private val service: MarketCandleService,
) {

    @GetMapping("/api/market/{market}/candles")
    fun candles(
        @PathVariable market: Market,
        @RequestParam interval: String,
        @RequestParam(defaultValue = "90") count: Int,
    ): ApiResponse<List<MarketCandleItem>> {
        val candles = when (interval) {
            "1d" -> service.daily(market, count)
            "1m" -> service.minuteToday(market)
            else -> emptyList()
        }
        return ApiResponse.ok(candles.map { it.toItem() })
    }

    data class MarketCandleItem(
        val date: String, // yyyy-MM-dd (KST)
        val time: String, // HH:mm:ss (KST) — 1d는 항상 00:00:00
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    private fun TossCandle.toItem() = MarketCandleItem(
        date = timestamp.toLocalDate().toString(),
        time = timestamp.toLocalTime().toString(),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
    )
}
