package at.backend.overseasleadingstock.presentation

import at.backend.library.web.ApiResponse
import at.backend.overseasleadingstock.application.OverseasLeadingStockService
import at.backend.overseasleadingstock.presentation.response.OverseasDailyCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/overseas-leading-stocks")
class OverseasLeadingStockController(
    private val service: OverseasLeadingStockService,
) {

    /**
     * 해외주식 거래대금순위 — 나스닥·뉴욕·아멕스 통합 상위 40위.
     * minChangeRate: 당일 등락률 임계값(%). 사용자가 -12~7 중 선택, 미지정 시 기본값.
     */
    @GetMapping("/ranking")
    fun getRanking(
        @RequestParam(required = false) minChangeRate: Int?,
    ): ApiResponse<List<OverseasStockRankItem>> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble()
            ?: DEFAULT_MIN_CHANGE_RATE
        return ApiResponse.ok(service.getRanking(rate))
    }

    /** 종목 일봉 — 일봉 차트용. */
    @GetMapping("/{exchange}/{symbol}/daily-candles")
    fun getDailyCandles(
        @PathVariable exchange: String,
        @PathVariable symbol: String,
    ): ApiResponse<List<OverseasDailyCandleItem>> =
        ApiResponse.ok(service.dailyCandles(exchange.uppercase(), symbol.uppercase()))

    companion object {
        private const val MIN_CHANGE_RATE = -12
        private const val MAX_CHANGE_RATE = 7
        private const val DEFAULT_MIN_CHANGE_RATE = 7.0
    }
}
