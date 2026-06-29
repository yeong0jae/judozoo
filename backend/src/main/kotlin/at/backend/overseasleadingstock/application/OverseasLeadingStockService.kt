package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.presentation.response.FilterResultItem
import at.backend.overseasleadingstock.presentation.response.OverseasDailyCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasMinuteCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasStockDetailResponse
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import at.backend.platform.kis.client.KisOverseasChartClient
import at.backend.platform.kis.client.KisOverseasProductClient
import at.backend.platform.kis.client.KisOverseasRankingClient
import org.springframework.stereotype.Service

@Service
class OverseasLeadingStockService(
    private val rankingClient: KisOverseasRankingClient,
    private val chartClient: KisOverseasChartClient,
    private val productClient: KisOverseasProductClient,
) {

    /**
     * 통합 거래대금 60위 컷 → ETF 제외한 풀. 거래대금 내림차순으로 순위 재부여.
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
     * 미국 3개 거래소(나스닥·뉴욕·아멕스)를 합쳐 거래대금 상위 60위.
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
     * 종목 상세 — 필터 A(거래대금순위)·B(당일등락률)·C(시가총액) 평가.
     * A·B는 후보 풀에서, C는 상품기본정보(상장주식수×현재가)로 산출.
     */
    fun evaluateStock(exchange: String, symbol: String): OverseasStockDetailResponse {
        val stock = rankingPool().find { it.exchange == exchange && it.symbol == symbol }
            ?: throw NoSuchElementException("후보에 없는 종목: $exchange:$symbol")
        val marketCap = productClient.fetchMarketCap(exchange, symbol)

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
                actualValue = marketCap?.let { "$${it / 1_000_000}M" } ?: "조회 불가",
                passed = marketCap != null && marketCap >= MIN_MARKET_CAP_USD,
            ),
        )
        return OverseasStockDetailResponse(
            exchange = stock.exchange,
            symbol = stock.symbol,
            name = stock.name,
            ename = stock.ename,
            price = stock.price,
            rate = stock.rate,
            marketCap = marketCap,
            filterResults = filters,
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
        private const val TOP_N = 60
        private const val TOP_RANK_ALWAYS_INCLUDED = 3
        private const val MIN_CHANGE_RATE_PCT = 5.0          // 상세 B: 당일 등락률 하한
        private const val MIN_MARKET_CAP_USD = 2_000_000_000L // 상세 C: 시가총액 $2B 하한

        // ETF/ETN 발행사 브랜드 + 명시 키워드. 미국 거래대금 상위 ETF 대부분을 커버.
        private val ETF_KEYWORDS = listOf(
            " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
            "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
        )
    }
}
