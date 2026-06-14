package at.backend.leadingstock.application

import at.backend.leadingstock.application.filter.DailyHighPositionFilter
import at.backend.leadingstock.application.filter.DailyPriceChangeFilter
import at.backend.leadingstock.application.filter.EtfExclusionFilter
import at.backend.leadingstock.application.filter.FilterChain
import at.backend.leadingstock.application.filter.FilterEvaluationResult
import at.backend.leadingstock.application.filter.MarketCapFilter
import at.backend.leadingstock.application.filter.OpeningPriceFilter
import at.backend.leadingstock.application.filter.PrevDayCloseFilter
import at.backend.leadingstock.application.filter.PriceAboveOpenFilter
import at.backend.leadingstock.application.filter.ProgramNetBuyFilter
import at.backend.leadingstock.application.filter.StockFilter
import at.backend.leadingstock.application.filter.TradingValueRankFilter
import at.backend.leadingstock.domain.DailyCandles
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.leadingstock.domain.MinuteCandles
import at.backend.leadingstock.domain.SwingHighSignal
import at.backend.library.time.TimeProvider
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import at.backend.platform.kiwoom.client.KiwoomProgramClient
import at.backend.platform.kiwoom.client.KiwoomThemeClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
class LeadingStockService(
    private val marketClient: KiwoomMarketClient,
    private val programClient: KiwoomProgramClient,
    @Suppress("unused") private val themeClient: KiwoomThemeClient,
    private val criteria: LeadingStockCriteriaProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Phase 1 필터만 적용한 후보 종목 (거래대금 순위 + 당일 등락률).
     * candidateStocks 캐시(5s TTL)로 짧은 폴링 시 키움 API 직접 호출 회피.
     */
    @Cacheable("candidateStocks")
    fun findCandidateStocks(): List<LeadingStockSnapshot> {
        log.info("Fetching candidate stocks (Phase 1 only)")

        val candidates = marketClient.fetchTopTradingValueStocks(50)
        log.info("Fetched {} candidates from trading value ranking", candidates.size)

        // 거래대금 1~3위는 ETF/등락률 무관 항상 포함 — 시장 톤 기준점
        val topThree = candidates.take(TOP_RANK_ALWAYS_INCLUDED)

        val phase1Filters = FilterChain(
            listOf(
                EtfExclusionFilter(),                // ETF/ETN 제외
                TradingValueRankFilter(criteria),
                DailyPriceChangeFilter(criteria),
            ),
        )
        val survivors = phase1Filters.apply(candidates)

        // 거래대금 순 정렬 유지 + 중복 제거 (top3가 survivors와 겹치면 자연 dedupe)
        val merged = (topThree + survivors).distinctBy { it.stockCode }
        log.info("Phase 1 survivors: {} (top3 forced + {} filter-pass)", merged.size, survivors.size)
        return merged
    }

    /** 특정 종목에 대해 모든 필터(A~H) 평가 + 상대거래량 — 상세 보기에서 사용 */
    fun evaluateStock(stockCode: String): StockEvaluation {
        log.info("Evaluating stock: {}", stockCode)

        val topTradingStocks = marketClient.fetchTopTradingValueStocks(50)
        val rankInfo = topTradingStocks.find { it.stockCode == stockCode }

        val baseStock = marketClient.fetchStockDetail(stockCode)
            ?: throw NoSuchElementException("종목을 찾을 수 없습니다: $stockCode")

        val stock = if (rankInfo != null) {
            baseStock.copy(
                tradingValueRank = rankInfo.tradingValueRank,
                accumulatedTradingValue = rankInfo.accumulatedTradingValue,
            )
        } else baseStock

        // 일봉 1회 조회, 필터 E·F·G에서 공유
        val dailyCandles = marketClient.fetchDailyCandles(stockCode, 60)

        val allFilters: List<StockFilter> = listOf(
            // Phase 1
            TradingValueRankFilter(criteria),       // A: 거래대금 상위 30위
            DailyPriceChangeFilter(criteria),       // B: 당일 등락률 >= 5%
            // Phase 2
            MarketCapFilter(criteria),              // C: 시가총액 >= 3000억
            PriceAboveOpenFilter(),                 // D: 현재가 >= 시가
            PrevDayCloseFilter(criteria) { dailyCandles.take(3) },          // E
            OpeningPriceFilter(criteria) { dailyCandles.take(3) },          // F
            DailyHighPositionFilter(criteria) { dailyCandles },             // G
            ProgramNetBuyFilter(criteria) { programClient.fetchProgramNetBuy(it) }, // H
        )

        val results = allFilters.map { it.evaluate(stock) }
        val relativeVolume = DailyCandles(dailyCandles)
            .relativeVolume(timeProvider.today(), RVOL_LOOKBACK_DAYS)

        val swingHighSignal = MinuteCandles(marketClient.fetchMinuteCandles(stockCode))
            .lastSwingHighSignal(stock.currentPrice, criteria.swingHighPullbackRate)

        return StockEvaluation(stock, results, relativeVolume, swingHighSignal)
    }

    companion object {
        private const val TOP_RANK_ALWAYS_INCLUDED = 3
        private const val RVOL_LOOKBACK_DAYS = 20
    }
}

/**
 * 종목 상세 평가 결과 — 필터 평가 + 상대거래량(RVOL) + 직전 스윙 고점 돌파 시그널.
 * 데이터 없으면 각각 null.
 */
data class StockEvaluation(
    val stock: LeadingStockSnapshot,
    val filterResults: List<FilterEvaluationResult>,
    val relativeVolume: Double?,
    val swingHighSignal: SwingHighSignal?,
)
