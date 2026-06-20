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
import at.backend.leadingstock.domain.MinuteCandle
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
    private val themeClient: KiwoomThemeClient,
    private val criteria: LeadingStockCriteriaProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Phase 1 필터만 적용한 후보 종목 (거래대금 순위 + 당일 등락률).
     * 당일 등락률 임계값은 호출자(사용자 선택)가 지정하며, 캐시 키도 이 값으로 분리한다.
     * candidateStocks 캐시(5s TTL)로 짧은 폴링 시 키움 API 직접 호출 회피.
     */
    @Cacheable("candidateStocks", key = "#minDailyPriceChangeRate")
    fun findCandidateStocks(minDailyPriceChangeRate: Double): List<LeadingStockSnapshot> {
        log.info("Fetching candidate stocks (Phase 1 only), 등락률 >= {}%", minDailyPriceChangeRate)

        val candidates = marketClient.fetchTopTradingValueStocks(50)
        log.info("Fetched {} candidates from trading value ranking", candidates.size)

        val etfExclusion = EtfExclusionFilter()

        // 개별종목 거래대금 1~3위는 등락률 무관 항상 포함 — 시장 톤 기준점 (ETF/ETN은 제외)
        val topThree = candidates.filter(etfExclusion::filter).take(TOP_RANK_ALWAYS_INCLUDED)

        // 사용자 지정 등락률만 덮어쓴 임계값으로 Phase 1 필터 구성
        val effectiveCriteria = criteria.copy(minDailyPriceChangeRate = minDailyPriceChangeRate)
        val phase1Filters = FilterChain(
            listOf(
                etfExclusion,                        // ETF/ETN 제외
                TradingValueRankFilter(effectiveCriteria),
                DailyPriceChangeFilter(effectiveCriteria),
            ),
        )
        val survivors = phase1Filters.apply(candidates)

        // 거래대금 순 정렬 유지 + 중복 제거 (top3가 survivors와 겹치면 자연 dedupe)
        val merged = (topThree + survivors).distinctBy { it.stockCode }
        log.info("Phase 1 survivors: {} (top3 forced + {} filter-pass)", merged.size, survivors.size)
        return merged
    }

    /**
     * 종목이 속한 테마명 목록. 캐싱은 [KiwoomThemeClient.fetchThemesForStock]에서 처리하므로
     * (후보 목록·테마 캘린더 캡처가 같은 캐시 공유) 여기선 위임만 한다.
     */
    fun themesOf(stockCode: String): List<String> = themeClient.fetchThemesForStock(stockCode)

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

        val swingHighSignal = MinuteCandles(latestSessionMinuteCandles(stockCode))
            .dayHighSignal(stock.currentPrice)

        return StockEvaluation(stock, results, relativeVolume, swingHighSignal)
    }

    /**
     * 돌파 임박 레이더 — 후보를 당일 고가 돌파에 가까운 순으로 정렬.
     * 후보별 ka10001(stockDetail, 5s 캐시)로 당일 고가·현재가를 받아 gap%를 계산한다(분봉 미사용).
     */
    fun breakoutRadar(minDailyPriceChangeRate: Double): List<BreakoutRadarStock> =
        findCandidateStocks(minDailyPriceChangeRate).mapNotNull { c ->
            val detail = marketClient.fetchStockDetail(c.stockCode) ?: return@mapNotNull null
            val high = detail.highPrice
            val current = detail.currentPrice
            if (high <= 0 || current <= 0) return@mapNotNull null
            BreakoutRadarStock(
                stockCode = c.stockCode,
                stockName = c.stockName,
                currentPrice = current,
                dayHigh = high,
                gapRate = (high - current).toDouble() / current * 100,
            )
        }.sortedBy { it.gapRate }

    /**
     * 가장 최근 거래일의 분봉만 추린다. stockCode는 `_AL`(SOR 통합 = KRX+NXT, 애프터마켓 포함)로 들어온다.
     * ka10080은 base_dt 기준 과거 여러 날 분봉을 함께 내려주므로, 데이터에 존재하는 최신 거래일로 필터링해야
     * 전고점이 다른 날 봉에서 잡히지 않는다(장중엔 당일, 장 마감 후엔 직전 세션).
     */
    private fun latestSessionMinuteCandles(stockCode: String): List<MinuteCandle> {
        val candles = marketClient.fetchMinuteCandles(stockCode)
        val latestDay = candles.maxOfOrNull { it.dateTime.toLocalDate() } ?: return emptyList()
        return candles.filter { it.dateTime.toLocalDate() == latestDay }
    }

    companion object {
        private const val TOP_RANK_ALWAYS_INCLUDED = 3
        private const val RVOL_LOOKBACK_DAYS = 20
    }
}

/** 돌파 레이더 한 종목 — 당일 고가(돌파선) 대비 현재가 갭. */
data class BreakoutRadarStock(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val dayHigh: Long,
    val gapRate: Double, // 돌파까지 남은 상승률(%), 고가 도달 시 0
)

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
