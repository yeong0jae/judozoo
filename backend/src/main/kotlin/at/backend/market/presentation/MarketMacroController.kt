package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.MacroQuotes
import at.backend.market.application.MacroTarget
import at.backend.market.application.MarketMacroService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 매크로 지표(원달러·WTI) 시세·캔들 — 야후. (시황분석 매크로 상세용) */
@RestController
class MarketMacroController(
    private val service: MarketMacroService,
) {

    /** 원달러·WTI 시세 — 상단 스트립이 한 번에 받아 간다. */
    @GetMapping("/api/market/macro/quotes")
    fun quotes(): ApiResponse<MacroQuotes> = ApiResponse.ok(service.quotes())

    /** 매크로 캔들 — target "USD_KRW"/"WTI", interval "1d"/"1m". */
    @GetMapping("/api/market/macro/candles")
    fun candles(
        @RequestParam target: MacroTarget,
        @RequestParam interval: String,
    ): ApiResponse<List<MacroCandleItem>> =
        ApiResponse.ok(
            service.candles(target, interval).map {
                MacroCandleItem(it.date, it.time, it.open, it.high, it.low, it.close, it.volume)
            },
        )

    data class MacroCandleItem(
        val date: String, // yyyy-MM-dd (KST)
        val time: String, // HH:mm:ss (1d는 00:00:00)
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )
}
