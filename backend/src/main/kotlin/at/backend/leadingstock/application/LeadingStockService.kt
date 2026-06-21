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
import at.backend.leadingstock.application.filter.SpacExclusionFilter
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
        val spacExclusion = SpacExclusionFilter()

        // 개별종목 거래대금 1~3위는 등락률 무관 항상 포함 — 시장 톤 기준점 (ETF/ETN·스팩은 제외)
        val topThree = candidates
            .filter(etfExclusion::filter)
            .filter(spacExclusion::filter)
            .take(TOP_RANK_ALWAYS_INCLUDED)

        // 사용자 지정 등락률만 덮어쓴 임계값으로 Phase 1 필터 구성
        val effectiveCriteria = criteria.copy(minDailyPriceChangeRate = minDailyPriceChangeRate)
        val phase1Filters = FilterChain(
            listOf(
                etfExclusion,                        // ETF/ETN 제외
                spacExclusion,                       // 스팩 제외
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
     * 후보별 분봉(dayHighSignal, 상세와 동일 로직)으로 돌파선·형성시각·gap%를 구하고,
     * 현재가·거래대금은 후보 스냅샷에서 가져온다. 분봉은 30s 캐시.
     */
    fun breakoutRadar(minDailyPriceChangeRate: Double): List<BreakoutRadarStock> =
        findCandidateStocks(minDailyPriceChangeRate).mapNotNull { c ->
            val signal = MinuteCandles(latestSessionMinuteCandles(c.stockCode))
                .dayHighSignal(c.currentPrice) ?: return@mapNotNull null
            BreakoutRadarStock(
                stockCode = c.stockCode,
                stockName = c.stockName,
                currentPrice = c.currentPrice,
                priceChangeRate = c.priceChangeRate,
                dayHigh = signal.peakPrice,
                peakAt = signal.peakAt,
                gapRate = signal.gapRate,
                tradingValue = c.accumulatedTradingValue,
            )
        }.sortedBy { it.gapRate }

    /**
     * 분봉 거래대금 스파이크 — 주도주 후보(거래대금 상위 + 등락률 필터) 중 최신 1분봉 거래대금이
     * 직전 평균 대비 급증한 종목을 배율 내림차순으로.
     */
    fun volumeSpikes(minDailyPriceChangeRate: Double): List<VolumeSpikeStock> =
        findCandidateStocks(minDailyPriceChangeRate)
            .mapNotNull { s ->
                val spike = MinuteCandles(latestSessionMinuteCandles(s.stockCode))
                    .volumeSpike(SPIKE_BASELINE_BARS) ?: return@mapNotNull null
                if (spike.ratio < SPIKE_RATIO_MIN || spike.latestTradingValue < SPIKE_MIN_TRADING_VALUE) {
                    return@mapNotNull null
                }
                VolumeSpikeStock(
                    stockCode = s.stockCode,
                    stockName = s.stockName,
                    currentPrice = s.currentPrice,
                    priceChangeRate = s.priceChangeRate,
                    minuteTradingValue = spike.latestTradingValue,
                    spikeRatio = spike.ratio,
                    at = spike.at,
                )
            }.sortedByDescending { it.spikeRatio }

    /**
     * 시그널 전이 적재 폴러용 — 후보별 분봉 1회로 돌파 갭·전고점·스파이크 배율을 함께 읽는다.
     * spikeRatio는 거래대금 임계를 넘긴 봉만 채우고(미달/봉없음이면 null로 둬 히스테리시스 해제에 쓰이게 한다),
     * 분봉이 없으면 gapRate·peakPrice도 null.
     */
    fun signalReadings(minDailyPriceChangeRate: Double): List<CandidateSignalReading> =
        findCandidateStocks(minDailyPriceChangeRate).map { c ->
            val candles = MinuteCandles(latestSessionMinuteCandles(c.stockCode))
            val high = candles.dayHighSignal(c.currentPrice)
            val spike = candles.volumeSpike(SPIKE_BASELINE_BARS)
                ?.takeIf { it.latestTradingValue >= SPIKE_MIN_TRADING_VALUE }
            CandidateSignalReading(
                stockCode = c.stockCode,
                stockName = c.stockName,
                currentPrice = c.currentPrice,
                priceChangeRate = c.priceChangeRate,
                tradingValue = c.accumulatedTradingValue,
                gapRate = high?.gapRate,
                peakPrice = high?.peakPrice,
                spikeRatio = spike?.ratio,
            )
        }

    /**
     * 상세 캔들차트용 — 최근 [CHART_SESSION_DAYS]거래일 1분봉을 시간 오름차순으로. (ka10080 30s 캐시 공유)
     * ka10080 한 페이지는 직전일 일부(애프터마켓 부근)까지만 닿으므로, 가진 데이터의 가장 이른 날을
     * base_dt로 이어 호출하며 거래일을 하나씩 채운다(당일 호출은 신호와 캐시 공유, 과거분만 추가).
     */
    fun minuteCandles(stockCode: String): List<MinuteCandle> {
        val all = marketClient.fetchMinuteCandles(stockCode).toMutableList()
        // base_dt=X 호출은 X 세션 전체 + 직전일 일부를 주므로, 가장 이른 날을 base_dt로 이어 받으면
        // 거래일이 하나씩 늘며 채워진다. 필요 일수+1(끝 날은 부분만 와 버림)까지, 더 과거가 없으면 중단.
        while (all.map { it.dateTime.toLocalDate() }.distinct().size <= CHART_SESSION_DAYS) {
            val oldestDay = all.minOfOrNull { it.dateTime.toLocalDate() } ?: break
            val before = all.map { it.dateTime.toLocalDate() }.distinct().size
            all += marketClient.fetchHistoricalMinuteCandles(stockCode, oldestDay)
            if (all.map { it.dateTime.toLocalDate() }.distinct().size == before) break // 데이터 소진
        }
        val recentDays = all.map { it.dateTime.toLocalDate() }
            .distinct().sortedDescending().take(CHART_SESSION_DAYS).toSet()
        return all.filter { it.dateTime.toLocalDate() in recentDays }
            .distinctBy { it.dateTime }
            .sortedBy { it.dateTime }
    }

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
        private const val SPIKE_BASELINE_BARS = 20      // 직전 평균 산정 봉 수
        private const val SPIKE_RATIO_MIN = 3.0         // 최소 배율
        private const val SPIKE_MIN_TRADING_VALUE = 1_000_000_000L // 최신 1분봉 최소 거래대금(원)
        private const val CHART_SESSION_DAYS = 3 // 상세 차트 표시 거래일 수(당일 포함)
    }
}

/**
 * 시그널 전이 판정용 후보 한 종목의 측정값 + 적재 컨텍스트.
 * gapRate·peakPrice·spikeRatio는 신호가 없으면 null(분봉 미존재 또는 거래대금 미달).
 */
data class CandidateSignalReading(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val tradingValue: Long,
    val gapRate: Double?,
    val peakPrice: Long?,
    val spikeRatio: Double?,
)

/** 분봉 거래대금 스파이크 한 종목. */
data class VolumeSpikeStock(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val minuteTradingValue: Long, // 최신 1분봉 거래대금(원)
    val spikeRatio: Double,       // 직전 평균 대비 배율
    val at: java.time.LocalDateTime,
)

/** 돌파 레이더 한 종목 — 당일 고가(돌파선) 대비 현재가 갭. */
data class BreakoutRadarStock(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double, // 당일 등락률(%)
    val dayHigh: Long,
    val peakAt: java.time.LocalDateTime, // 돌파선(고가) 형성 분봉 시각
    val gapRate: Double, // 돌파까지 남은 상승률(%), 고가 도달 시 0
    val tradingValue: Long, // 당일 누적 거래대금(원)
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
