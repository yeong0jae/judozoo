package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.FuturesQuote
import at.backend.market.application.MarketFuturesService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 코스피 선물(근월물) 시세·캔들 — KIS 국내선물옵션. (시황분석 지수 상세용) */
@RestController
class MarketFuturesController(
    private val service: MarketFuturesService,
) {

    /** 근월물 시세 요약 — 선물·현물·베이시스·괴리율·미결제. */
    @GetMapping("/api/market/futures/kospi/quote")
    fun quote(): ApiResponse<FuturesQuote?> = ApiResponse.ok(service.quote())

    /** 근월물 캔들 — interval "1d"/"1m". */
    @GetMapping("/api/market/futures/kospi/candles")
    fun candles(
        @RequestParam interval: String,
        @RequestParam(defaultValue = "90") count: Int,
    ): ApiResponse<List<FuturesCandleItem>> =
        ApiResponse.ok(
            service.candles(interval, count).map {
                FuturesCandleItem(it.date, it.time, it.open, it.high, it.low, it.close, it.volume)
            },
        )

    data class FuturesCandleItem(
        val date: String, // yyyy-MM-dd (KST)
        val time: String, // HH:mm:ss (1d는 00:00:00)
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )
}
