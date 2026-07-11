package at.backend.market.presentation

import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketInvestorService
import at.backend.market.application.SessionNet
import at.backend.market.domain.MarketInvestorTrading
import at.backend.platform.toss.client.TossMarketIndicatorClient.MarketInvestorRecord
import at.backend.stock.domain.Market
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

/** 시장 투자자 매매대금 — 장중 스냅샷/세션/일별. (종가 베팅 코스피 상세용) */
@RestController
class MarketInvestorController(
    private val service: MarketInvestorService,
    private val timeProvider: TimeProvider,
) {

    /** 그날 장중 스냅샷 시계열 — 토스 갱신주기(sourceUpdatedAt) 확인용. */
    @GetMapping("/api/market/{market}/investor/intraday")
    fun intraday(
        @PathVariable market: Market,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ApiResponse<List<IntradayPoint>> {
        val d = date ?: timeProvider.today()
        return ApiResponse.ok(service.intraday(market, d).map { it.toPoint() })
    }

    /** 세션별(오전/오후/막판) 순매수. */
    @GetMapping("/api/market/{market}/investor/sessions")
    fun sessions(
        @PathVariable market: Market,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ApiResponse<List<SessionNet>> {
        val d = date ?: timeProvider.today()
        return ApiResponse.ok(service.sessions(market, d))
    }

    /** 최근 N일 일별 순매수(외/기/개/기타법인 + 기관 세부). */
    @GetMapping("/api/market/{market}/investor/daily")
    fun daily(
        @PathVariable market: Market,
        @RequestParam(defaultValue = "10") count: Int,
    ): ApiResponse<List<MarketInvestorRecord>> =
        ApiResponse.ok(service.dailyHistory(market, count))

    data class IntradayPoint(
        val capturedAt: LocalDateTime,
        val sourceUpdatedAt: LocalDateTime,
        val individualEok: Long,
        val foreignEok: Long,
        val institutionEok: Long,
        val otherCorpEok: Long,
    )

    private fun MarketInvestorTrading.toPoint() = IntradayPoint(
        capturedAt = capturedAt,
        sourceUpdatedAt = sourceUpdatedAt,
        individualEok = individualEok,
        foreignEok = foreignEok,
        institutionEok = institutionEok,
        otherCorpEok = otherCorpEok,
    )
}
