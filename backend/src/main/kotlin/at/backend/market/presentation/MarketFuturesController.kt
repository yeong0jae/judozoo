package at.backend.market.presentation

import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import at.backend.market.application.FuturesInvestorDay
import at.backend.market.application.FuturesQuote
import at.backend.market.application.FuturesSessionNet
import at.backend.market.application.MarketFuturesInvestorService
import at.backend.market.application.MarketFuturesService
import at.backend.market.application.MarketNasdaqIndexService
import at.backend.market.application.MarketNightFuturesService
import at.backend.market.application.NasdaqIndexQuote
import at.backend.market.application.NightFuturesQuote
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 코스피 선물(근월물) 시세·캔들·수급 — KIS 국내선물옵션. (시황분석 지수 상세용) */
@RestController
class MarketFuturesController(
    private val service: MarketFuturesService,
    private val nightService: MarketNightFuturesService,
    private val nasdaqIndexService: MarketNasdaqIndexService,
    private val investorService: MarketFuturesInvestorService,
    private val timeProvider: TimeProvider,
) {

    /** 근월물 시세 요약 — 선물·현물·베이시스·괴리율·미결제. */
    @GetMapping("/api/market/futures/kospi/quote")
    fun quote(): ApiResponse<FuturesQuote?> = ApiResponse.ok(service.quote())

    /** 최근 N거래일 일별 투자자 순매수(계약) — 폴러가 적재한 날만. */
    @GetMapping("/api/market/futures/kospi/investor/daily")
    fun daily(
        @RequestParam(defaultValue = "10") count: Int,
    ): ApiResponse<List<FuturesInvestorDay>> =
        ApiResponse.ok(investorService.dailyHistory(count))

    /** 세션별(오전/오후/막판) 투자자 순매수(계약). */
    @GetMapping("/api/market/futures/kospi/investor/sessions")
    fun sessions(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ApiResponse<List<FuturesSessionNet>> =
        ApiResponse.ok(investorService.sessions(date ?: timeProvider.today()))

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

    /** 야간선물 시세 — 현재가·갭(직전 정규장 종가 대비)·미결제. */
    @GetMapping("/api/market/futures/night/quote")
    fun nightQuote(): ApiResponse<NightFuturesQuote?> = ApiResponse.ok(nightService.quote())

    /** 야간선물 캔들 — interval "1d"/"1m"(최근 세션). */
    @GetMapping("/api/market/futures/night/candles")
    fun nightCandles(
        @RequestParam interval: String,
        @RequestParam(defaultValue = "90") count: Int,
    ): ApiResponse<List<FuturesCandleItem>> =
        ApiResponse.ok(
            nightService.candles(interval, count).map {
                FuturesCandleItem(it.date, it.time, it.open, it.high, it.low, it.close, it.volume)
            },
        )

    /** 나스닥 종합지수(^IXIC) 시세 — 야후. */
    @GetMapping("/api/market/nasdaq/quote")
    fun nasdaqIndexQuote(): ApiResponse<NasdaqIndexQuote?> = ApiResponse.ok(nasdaqIndexService.quote())

    /** 나스닥 종합지수 캔들 — interval "1d"/"1m". */
    @GetMapping("/api/market/nasdaq/candles")
    fun nasdaqIndexCandles(
        @RequestParam interval: String,
    ): ApiResponse<List<FuturesCandleItem>> =
        ApiResponse.ok(
            nasdaqIndexService.candles(interval).map {
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
