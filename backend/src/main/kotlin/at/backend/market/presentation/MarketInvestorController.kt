package at.backend.market.presentation

import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketInvestorDay
import at.backend.market.application.MarketInvestorService
import at.backend.market.application.SessionNet
import at.backend.stock.domain.Market
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 시장 투자자 순매수 — 키움 ka10051. 일별 히스토리 + 세션(시간대). (시황분석 지수 상세용) */
@RestController
class MarketInvestorController(
    private val service: MarketInvestorService,
    private val timeProvider: TimeProvider,
) {

    /** 최근 N일 일별 순매수(외/기/개/기타법인 + 기관 세부). */
    @GetMapping("/api/market/{market}/investor/daily")
    fun daily(
        @PathVariable market: Market,
        @RequestParam(defaultValue = "10") count: Int,
    ): ApiResponse<List<MarketInvestorDay>> =
        ApiResponse.ok(service.dailyHistory(market, count))

    /** 세션별(오전/오후/막판) 순매수. */
    @GetMapping("/api/market/{market}/investor/sessions")
    fun sessions(
        @PathVariable market: Market,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ApiResponse<List<SessionNet>> {
        val d = date ?: timeProvider.today()
        return ApiResponse.ok(service.sessions(market, d))
    }
}
