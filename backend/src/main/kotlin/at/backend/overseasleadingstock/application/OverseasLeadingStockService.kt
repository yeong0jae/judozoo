package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.presentation.response.FilterResultItem
import at.backend.overseasleadingstock.presentation.response.OverseasBreakoutRadarItem
import at.backend.overseasleadingstock.presentation.response.OverseasDailyCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasMinuteCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasStockDetailResponse
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import at.backend.overseasleadingstock.presentation.response.OverseasSwingHighSignal
import at.backend.leadingstock.domain.SpikeDirection
import at.backend.platform.kis.client.KisOverseasChartClient
import at.backend.platform.kis.client.KisOverseasChartClient.OverseasMinuteCandle
import at.backend.platform.kis.client.KisOverseasProductClient
import at.backend.platform.kis.client.KisOverseasRankingClient
import org.springframework.stereotype.Service

@Service
class OverseasLeadingStockService(
    private val rankingClient: KisOverseasRankingClient,
    private val chartClient: KisOverseasChartClient,
    private val productClient: KisOverseasProductClient,
    private val minuteStore: OverseasMinuteCandleStore,
) {

    /**
     * 실시간 폴러용 — 후보별 최신 분봉을 스토어에 누적 병합한 뒤 돌파선·스파이크 측정값을 만든다.
     * 종목당 분봉 1호출(120건)만, 누적분으로 전고점(돌파선)을 잡는다.
     */
    fun signalReadings(minChangeRate: Double): List<OverseasCandidateReading> =
        getRanking(minChangeRate).map { stock ->
            // 첫 등장 종목은 2거래일 페이징으로 seed(전고점 정확), 이후엔 최신 1페이지만 누적
            val fresh = if (minuteStore.has(stock.exchange, stock.symbol)) {
                chartClient.fetchLatestMinutes(stock.exchange, stock.symbol)
            } else {
                chartClient.fetchMinuteCandles(stock.exchange, stock.symbol)
            }
            minuteStore.merge(stock.exchange, stock.symbol, fresh)
            val stored = minuteStore.candles(stock.exchange, stock.symbol)

            val peak = stored.maxByOrNull { it.high }
            val gapRate = peak?.takeIf { stock.price > 0 }?.let { (it.high - stock.price) / stock.price * 100 }
            val spike = computeSpike(stored)

            OverseasCandidateReading(
                exchange = stock.exchange,
                symbol = stock.symbol,
                name = stock.name,
                price = stock.price,
                rate = stock.rate,
                tradingValue = stock.tradingValue,
                gapRate = gapRate,
                peakPrice = peak?.high,
                spikeRatio = spike?.ratio,
                minuteTradingValue = spike?.latestTradingValue,
                spikeDirection = spike?.direction,
            )
        }

    /** 최신 1분봉 거래대금이 직전 [SPIKE_BASELINE_BARS]봉 평균 대비 몇 배인지. 최소 거래대금 미달이면 null. */
    private fun computeSpike(candles: List<OverseasMinuteCandle>): SpikeMeasure? {
        if (candles.size < 2) return null
        val latest = candles.last()
        if (latest.tradingValue < SPIKE_MIN_TRADING_VALUE) return null
        val baseline = candles.dropLast(1).takeLast(SPIKE_BASELINE_BARS)
        val avg = baseline.map { it.tradingValue }.average()
        if (avg <= 0) return null
        val direction = when {
            latest.close > latest.open -> SpikeDirection.BUY
            latest.close < latest.open -> SpikeDirection.SELL
            else -> SpikeDirection.FLAT
        }
        return SpikeMeasure(latest.tradingValue, latest.tradingValue / avg, direction)
    }

    private data class SpikeMeasure(
        val latestTradingValue: Double,
        val ratio: Double,
        val direction: SpikeDirection,
    )

    /**
     * 통합 거래대금 40위 컷 → ETF 제외한 풀. 거래대금 내림차순으로 순위 재부여.
     * getRanking(전시)·evaluateStock(상세)이 공유하는 후보 풀.
     */
    private fun rankingPool(): List<OverseasStockRankItem> =
        EXCHANGES
            .flatMap { excd -> rankingClient.fetchTradingValueRanking(excd).map { it.toRankItem() } }
            .sortedByDescending { it.tradingValue }
            .take(TOP_N)
            .filterNot { it.isEtf() }
            .mapIndexed { i, item -> item.copy(rank = i + 1) }

    /**
     * 미국 3개 거래소(나스닥·뉴욕·아멕스)를 합쳐 거래대금 상위 40위.
     * 국내와 동일한 흐름: 거래대금 1~3위는 등락률 무관 항상 포함,
     * 나머지는 당일 등락률이 [minChangeRate] 이상인 것만 통과.
     */
    fun getRanking(minChangeRate: Double): List<OverseasStockRankItem> {
        val pool = rankingPool()
        val topThree = pool.take(TOP_RANK_ALWAYS_INCLUDED)
        val rest = pool.drop(TOP_RANK_ALWAYS_INCLUDED).filter { it.rate >= minChangeRate }
        return (topThree + rest).mapIndexed { i, item -> item.copy(rank = i + 1) }
    }

    /**
     * 돌파 현황 — 후보를 돌파선(누적 분봉 최고가) 돌파에 가까운 순으로.
     * 폴러가 채워둔 스토어를 읽어 추가 분봉 호출 없이 전고점·갭을 구한다(미국장 폴러가 누적). 누적분 없는 종목은 제외.
     */
    fun breakoutRadar(minChangeRate: Double): List<OverseasBreakoutRadarItem> =
        getRanking(minChangeRate).mapNotNull { stock ->
            if (stock.price <= 0) return@mapNotNull null
            val peak = minuteStore.candles(stock.exchange, stock.symbol).maxByOrNull { it.high }
                ?: return@mapNotNull null
            OverseasBreakoutRadarItem(
                exchange = stock.exchange,
                symbol = stock.symbol,
                name = stock.name,
                price = stock.price,
                rate = stock.rate,
                tradingValue = stock.tradingValue,
                dayHigh = peak.high,
                peakAt = peak.dateTime,
                gapRate = (peak.high - stock.price) / stock.price * 100,
            )
        }.sortedBy { it.gapRate }

    /**
     * 종목 상세 — 필터 A(거래대금순위)·B(당일등락률)·C(시가총액) 평가.
     * A·B는 후보 풀에서, C는 상품기본정보(상장주식수×현재가)로 산출.
     */
    fun evaluateStock(exchange: String, symbol: String): OverseasStockDetailResponse {
        val stock = rankingPool().find { it.exchange == exchange && it.symbol == symbol }
            ?: throw NoSuchElementException("후보에 없는 종목: $exchange:$symbol")
        val marketCap = productClient.fetchMarketCap(exchange, symbol)

        // 분봉(차트와 캐시 공유) 최고가를 전고점(돌파선)으로
        val peak = chartClient.fetchMinuteCandles(exchange, symbol).maxByOrNull { it.high }
        val swingHigh = peak?.takeIf { stock.price > 0 }?.let {
            OverseasSwingHighSignal(
                peakPrice = it.high,
                peakAt = it.dateTime,
                gapRate = (it.high - stock.price) / stock.price * 100,
            )
        }

        val filters = listOf(
            FilterResultItem(
                filterName = "거래대금순위",
                criteriaDescription = "통합 상위 ${TOP_N}위 이내",
                actualValue = "${stock.rank}위",
                passed = stock.rank <= TOP_N,
            ),
            FilterResultItem(
                filterName = "당일 등락률",
                criteriaDescription = "${MIN_CHANGE_RATE_PCT.toInt()}% 이상",
                actualValue = "${"%+.2f".format(stock.rate)}%",
                passed = stock.rate >= MIN_CHANGE_RATE_PCT,
            ),
            FilterResultItem(
                filterName = "시가총액",
                criteriaDescription = "$${MIN_MARKET_CAP_USD / 1_000_000_000}B 이상",
                actualValue = marketCap?.let { formatUsdCap(it) } ?: "조회 불가",
                passed = marketCap != null && marketCap >= MIN_MARKET_CAP_USD,
            ),
        )
        return OverseasStockDetailResponse(
            exchange = stock.exchange,
            symbol = stock.symbol,
            name = stock.name,
            ename = stock.ename,
            rank = stock.rank,
            price = stock.price,
            diff = stock.diff,
            rate = stock.rate,
            tradingValue = stock.tradingValue,
            marketCap = marketCap,
            filterResults = filters,
            swingHighSignal = swingHigh,
        )
    }

    /** 종목 1분봉 (한국 시각순 오름차순). */
    fun minuteCandles(exchange: String, symbol: String): List<OverseasMinuteCandleItem> =
        chartClient.fetchMinuteCandles(exchange, symbol)
            .sortedBy { it.dateTime }
            .map {
                OverseasMinuteCandleItem(
                    time = it.dateTime,
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                    tradingValue = it.tradingValue,
                )
            }

    /** 종목 일봉 (일자 오름차순). */
    fun dailyCandles(exchange: String, symbol: String): List<OverseasDailyCandleItem> =
        chartClient.fetchDailyCandles(exchange, symbol)
            .sortedBy { it.date }
            .map {
                OverseasDailyCandleItem(
                    date = it.date.toString(),
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                )
            }

    /** 시가총액(달러)을 보기 좋은 단위로 — 1조 이상은 $X.XXT, 그 외는 $X,XXXB. */
    private fun formatUsdCap(usd: Long): String =
        if (usd >= 1_000_000_000_000L) "$%.2fT".format(usd / 1_000_000_000_000.0)
        else "$%,dB".format(usd / 1_000_000_000L)

    /**
     * 거래대금순위 API엔 ETF 구분 필드가 없어 영문명 키워드로 판별(휴리스틱).
     * 발행사 브랜드 위주로 잡아 일반기업 오탐을 줄인다 — TRUST·FUND 등 흔한 단어는 일부러 제외.
     */
    private fun OverseasStockRankItem.isEtf(): Boolean {
        val upper = ename.uppercase()
        return ETF_KEYWORDS.any { upper.contains(it) }
    }

    private fun KisOverseasRankingClient.OverseasRankItem.toRankItem(): OverseasStockRankItem {
        // rate(등락율)는 이미 부호 포함("-6.69"). diff(대비)는 절댓값이라 sign으로 방향 부여.
        val negative = sign.trim() in setOf("4", "5") // 4:하한가 5:하락
        val diffSign = if (negative) -1.0 else 1.0
        return OverseasStockRankItem(
            rank = 0, // 통합 정렬 후 재부여
            exchange = excd.trim(),
            symbol = symb.trim(),
            name = name.trim(),
            ename = ename.trim(),
            price = last.trim().toDoubleOrNull() ?: 0.0,
            diff = diffSign * (diff.trim().toDoubleOrNull() ?: 0.0),
            rate = rate.trim().toDoubleOrNull() ?: 0.0,
            tradingValue = tamt.trim().toDoubleOrNull() ?: 0.0,
        )
    }

    companion object {
        private val EXCHANGES = listOf("NAS", "NYS", "AMS")
        private const val TOP_N = 40
        private const val TOP_RANK_ALWAYS_INCLUDED = 3
        private const val MIN_CHANGE_RATE_PCT = 5.0          // 상세 B: 당일 등락률 하한
        private const val MIN_MARKET_CAP_USD = 2_000_000_000L // 상세 C: 시가총액 $2B 하한
        private const val SPIKE_BASELINE_BARS = 20            // 스파이크 직전 평균 산정 봉 수
        private const val SPIKE_MIN_TRADING_VALUE = 1_000_000.0 // 최신 1분봉 최소 거래대금($1M)

        // ETF/ETN 발행사 브랜드 + 명시 키워드. 미국 거래대금 상위 ETF 대부분을 커버.
        private val ETF_KEYWORDS = listOf(
            " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
            "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
        )
    }
}
