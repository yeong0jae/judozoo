package at.backend.market.presentation

import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketProgramService
import at.backend.market.application.ProgramDay
import at.backend.market.application.ProgramSessionNet
import at.backend.stock.domain.Market
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 시장(코스피/코스닥) 프로그램 매매 — 키움 ka90010. 시간대별(세션) + 일별. (시황분석 지수 상세용) */
@RestController
class MarketProgramController(
    private val service: MarketProgramService,
    private val timeProvider: TimeProvider,
) {

    /** 세션별(오전/오후/막판) 프로그램 순매수(억원). */
    @GetMapping("/api/market/{market}/program/sessions")
    fun sessions(
        @PathVariable market: Market,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ApiResponse<List<ProgramSessionNet>> =
        ApiResponse.ok(service.sessions(market, date ?: timeProvider.today()))

    /** 최근 N거래일 일별 프로그램 순매수(억원). */
    @GetMapping("/api/market/{market}/program/daily")
    fun daily(
        @PathVariable market: Market,
        @RequestParam(defaultValue = "10") count: Int,
    ): ApiResponse<List<ProgramDay>> =
        ApiResponse.ok(service.dailyHistory(market, count))
}
