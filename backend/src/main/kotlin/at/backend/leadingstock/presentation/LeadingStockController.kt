package at.backend.leadingstock.presentation

import at.backend.leadingstock.application.InvestorTrendService
import at.backend.leadingstock.application.LeadingStockCriteriaProperties
import at.backend.leadingstock.application.LeadingStockService
import at.backend.leadingstock.application.SignalEventService
import at.backend.leadingstock.presentation.response.BreakoutRadarItem
import at.backend.leadingstock.presentation.response.BreakoutRadarResponse
import at.backend.leadingstock.presentation.response.CandidateStockItem
import at.backend.leadingstock.presentation.response.CandidateStocksResponse
import at.backend.leadingstock.presentation.response.VolumeSpikeItem
import at.backend.leadingstock.presentation.response.VolumeSpikeResponse
import at.backend.leadingstock.presentation.response.FilterResultItem
import at.backend.leadingstock.presentation.response.InvestorTrendDayItem
import at.backend.leadingstock.presentation.response.LeadingStockDetailResponse
import at.backend.leadingstock.presentation.response.MinuteCandleItem
import at.backend.leadingstock.presentation.response.SignalEventItem
import at.backend.leadingstock.presentation.response.SignalEventsResponse
import at.backend.leadingstock.presentation.response.SwingHighSignalItem
import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/leading-stocks")
class LeadingStockController(
    private val leadingStockService: LeadingStockService,
    private val investorTrendService: InvestorTrendService,
    private val signalEventService: SignalEventService,
    private val criteria: LeadingStockCriteriaProperties,
    private val timeProvider: TimeProvider,
) {

    /**
     * Phase 1 후보 리스트 (거래대금 순위 + 등락률 필터만 통과).
     * minChangeRate: 당일 등락률 임계값(%). 사용자가 -7~7 중 선택, 미지정 시 설정 기본값.
     */
    @GetMapping("/candidates")
    fun getCandidateStocks(
        @RequestParam(required = false) minChangeRate: Int?,
    ): ApiResponse<CandidateStocksResponse> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble()
            ?: criteria.minDailyPriceChangeRate
        val candidates = leadingStockService.findCandidateStocks(rate)
        val items = candidates.mapIndexed { i, s ->
            val themes = leadingStockService.themesOf(s.stockCode)
            CandidateStockItem(
                rank = i + 1,
                stockCode = s.stockCode,
                stockName = s.stockName,
                currentPrice = s.currentPrice,
                priceChangeRate = s.priceChangeRate,
                accumulatedTradingValue = s.accumulatedTradingValue,
                themes = themes.take(MAX_THEME_CHIPS),
                themeCount = themes.size,
            )
        }
        return ApiResponse.ok(
            CandidateStocksResponse(
                queriedAt = timeProvider.now(),
                totalCount = items.size,
                stocks = items,
            ),
        )
    }

    /**
     * 돌파 임박 레이더 — 후보를 당일 고가 돌파에 가까운 순으로.
     * minChangeRate 미지정 시 설정 기본값(후보와 동일 풀).
     */
    @GetMapping("/breakout-radar")
    fun getBreakoutRadar(
        @RequestParam(required = false) minChangeRate: Int?,
    ): ApiResponse<BreakoutRadarResponse> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble()
            ?: criteria.minDailyPriceChangeRate
        val items = leadingStockService.breakoutRadar(rate).map { s ->
            val themes = leadingStockService.themesOf(s.stockCode)
            BreakoutRadarItem(
                stockCode = s.stockCode,
                stockName = s.stockName,
                currentPrice = s.currentPrice,
                priceChangeRate = s.priceChangeRate,
                dayHigh = s.dayHigh,
                peakAt = s.peakAt,
                gapRate = s.gapRate,
                tradingValue = s.tradingValue,
                themes = themes.take(MAX_THEME_CHIPS),
                themeCount = themes.size,
            )
        }
        return ApiResponse.ok(
            BreakoutRadarResponse(
                queriedAt = timeProvider.now(),
                totalCount = items.size,
                stocks = items,
            ),
        )
    }

    /** 분봉 거래대금 스파이크 — 주도주 후보 중 최신 1분봉 거래대금이 직전 평균 대비 급증한 종목. */
    @GetMapping("/volume-spikes")
    fun getVolumeSpikes(
        @RequestParam(required = false) minChangeRate: Int?,
    ): ApiResponse<VolumeSpikeResponse> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble()
            ?: criteria.minDailyPriceChangeRate
        val items = leadingStockService.volumeSpikes(rate).map { s ->
            VolumeSpikeItem(
                stockCode = s.stockCode,
                stockName = s.stockName,
                currentPrice = s.currentPrice,
                priceChangeRate = s.priceChangeRate,
                minuteTradingValue = s.minuteTradingValue,
                spikeRatio = s.spikeRatio,
                at = s.at,
            )
        }
        return ApiResponse.ok(
            VolumeSpikeResponse(
                queriedAt = timeProvider.now(),
                totalCount = items.size,
                stocks = items,
            ),
        )
    }

    /**
     * 시그널 전이 로그 — 그날 발생한 돌파/임박/스파이크 전이를 최신순으로.
     * date 미지정 시 오늘. 라이브 피드 + 종목 여정 화면이 같은 데이터를 쓴다.
     */
    @GetMapping("/signal-events")
    fun getSignalEvents(
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        date: java.time.LocalDate?,
    ): ApiResponse<SignalEventsResponse> {
        val day = date ?: timeProvider.today()
        val events = signalEventService.eventsOn(day).map { e ->
            SignalEventItem(
                occurredAt = e.occurredAt,
                stockCode = e.stockCode,
                stockName = e.stockName,
                eventType = e.eventType.name,
                currentPrice = e.currentPrice,
                priceChangeRate = e.priceChangeRate,
                tradingValue = e.tradingValue,
                gapRate = e.gapRate,
                spikeRatio = e.spikeRatio,
                theme = e.theme,
            )
        }
        return ApiResponse.ok(SignalEventsResponse(date = day, totalCount = events.size, events = events))
    }

    /** 종목 최신 거래일 1분봉 — 상세 캔들차트용. ka10080 30s 캐시를 상세 평가와 공유. */
    @GetMapping("/candidates/{stockCode}/minute-candles")
    fun getMinuteCandles(@PathVariable stockCode: String): ApiResponse<List<MinuteCandleItem>> {
        val candles = leadingStockService.minuteCandles(stockCode).map {
            MinuteCandleItem(
                time = it.dateTime,
                open = it.openPrice,
                high = it.highPrice,
                low = it.lowPrice,
                close = it.closePrice,
                volume = it.volume,
                tradingValue = it.tradingValue,
            )
        }
        return ApiResponse.ok(candles)
    }

    /** 종목별 일자별 외국인·기관·개인 순매수 추이 (단위: 백만원). */
    @GetMapping("/candidates/{stockCode}/investors")
    fun getInvestorTrend(@PathVariable stockCode: String): ApiResponse<List<InvestorTrendDayItem>> {
        val trend = investorTrendService.getTrend(stockCode)
        return ApiResponse.ok(
            trend.map {
                InvestorTrendDayItem(
                    date = it.date,
                    individualNet = it.individualNet,
                    foreignNet = it.foreignNet,
                    institutionNet = it.institutionNet,
                    individualNetNxt = it.individualNetNxt,
                    foreignNetNxt = it.foreignNetNxt,
                    institutionNetNxt = it.institutionNetNxt,
                )
            },
        )
    }

    /** 종목 상세 — 전체 필터(A~H) 평가 결과 + 상대거래량 */
    @GetMapping("/candidates/{stockCode}")
    fun getStockDetail(@PathVariable stockCode: String): ApiResponse<LeadingStockDetailResponse> {
        val eval = leadingStockService.evaluateStock(stockCode)
        val stock = eval.stock
        return ApiResponse.ok(
            LeadingStockDetailResponse(
                stockCode = stock.stockCode,
                stockName = stock.stockName,
                currentPrice = stock.currentPrice,
                priceChangeRate = stock.priceChangeRate,
                relativeVolume = eval.relativeVolume,
                themes = leadingStockService.themesOf(stock.stockCode),
                swingHighSignal = eval.swingHighSignal?.let {
                    SwingHighSignalItem(
                        peakPrice = it.peakPrice,
                        peakAt = it.peakAt,
                        gapRate = it.gapRate,
                    )
                },
                filterResults = eval.filterResults.map {
                    FilterResultItem(
                        filterName = it.filterName,
                        criteriaDescription = it.criteriaDescription,
                        actualValue = it.actualValue,
                        passed = it.passed,
                    )
                },
            ),
        )
    }

    companion object {
        private const val MIN_CHANGE_RATE = -7
        private const val MAX_CHANGE_RATE = 7
        private const val MAX_THEME_CHIPS = 2
    }
}
