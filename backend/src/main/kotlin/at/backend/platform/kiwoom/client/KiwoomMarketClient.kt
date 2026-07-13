package at.backend.platform.kiwoom.client

import at.backend.leadingstock.domain.DailyCandle
import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.leadingstock.domain.MinuteCandle
import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class KiwoomMarketClient(
    private val kiwoomRestClient: RestClient,
    private val properties: KiwoomApiProperties,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 키움 가격: +/- 부호 제거 후 절대값 반환 (키움은 전일대비 방향을 부호로 붙임) */
    private fun parseKiwoomPrice(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return 0L
        val cleaned = trimmed.removePrefix("+").removePrefix("-").replace("-", "")
        return cleaned.toLongOrNull() ?: 0L
    }

    /**
     * 거래대금 상위 종목 조회 (ka10032).
     * 5초 캐시로 findCandidateStocks(5초 폴링)와 evaluateStock(상세 클릭)이 동일 응답을 공유 —
     * 상세 클릭당 Kiwoom 호출을 줄여 rate limit 회피.
     * 빈 응답·오류는 throw — @Cacheable이 빈 결과를 저장해 후속 폴링이 5초 동안 빈 리스트를
     * 반환하는 사고를 막는다.
     */
    @Cacheable("topTradingValueStocks")
    fun fetchTopTradingValueStocks(count: Int = 50): List<LeadingStockSnapshot> =
        withKiwoomTokenRetry { fetchTopTradingValueStocksOnce(count) }

    private fun fetchTopTradingValueStocksOnce(count: Int): List<LeadingStockSnapshot> {
        val token = authClient.getAccessToken()
        log.info("Fetching top {} trading value stocks from Kiwoom API", count)

        val response = kiwoomRestClient.post()
            .uri("/api/dostk/rkinfo")
            .header("authorization", "Bearer $token")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("api-id", "ka10032")
            .body(
                mapOf(
                    "mrkt_tp" to "000",      // 000:전체
                    "mang_stk_incls" to "0", // 0:관리종목 미포함
                    "stex_tp" to "3",        // 3:KRX+NXT 통합
                ),
            )
            .retrieve()
            .body(TradingVolumeResponse::class.java)
            ?: throw IllegalStateException("Kiwoom trading value response is null")

        if (response.return_code != null && response.return_code != 0) {
            throw IllegalStateException(
                "Kiwoom trading value ranking error. code=${response.return_code} msg=${response.return_msg}",
            )
        }
        val items = response.trde_prica_upper
            ?: throw IllegalStateException(
                "Kiwoom trading value ranking returned no list. msg=${response.return_msg}",
            )

        return items.mapIndexed { index, item ->
            // trde_prica는 백만원 단위
            val tradingValueInMillion = item.trde_prica.toLongOrNull() ?: 0
            val tradingValueInWon = tradingValueInMillion * 1_000_000

            LeadingStockSnapshot(
                stockCode = item.stk_cd,
                stockName = item.stk_nm,
                currentPrice = parseKiwoomPrice(item.cur_prc),
                priceChangeRate = item.flu_rt.toDoubleOrNull() ?: 0.0,
                tradingValueRank = item.now_rank.toIntOrNull() ?: (index + 1),
                accumulatedTradingValue = tradingValueInWon,
            )
        }.take(count)
    }

    /**
     * Kiwoom 응답이 토큰 무효(8005)면 토큰 캐시를 무효화하고 1회 재시도.
     * 같은 app key를 여러 인스턴스가 공유할 때 다른 쪽이 새 토큰을 발급하면 이쪽 토큰이 즉시 무효화되는 케이스 대응.
     */
    private inline fun <T> withKiwoomTokenRetry(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        if (isInvalidTokenError(e.message)) {
            log.warn("Kiwoom 토큰 무효(8005) 감지 — 캐시 무효화 후 1회 재시도")
            authClient.invalidate()
            block()
        } else throw e
    }

    private fun isInvalidTokenError(msg: String?): Boolean =
        msg?.let { it.contains("8005") || it.contains("Token이 유효하지 않") } == true

    /** 등락률 상위 종목 조회 (ka10027) */
    fun fetchTopPriceChangeRateStocks(count: Int = 50): List<LeadingStockSnapshot> {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching top {} price change rate stocks from Kiwoom API", count)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/rkinfo")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10027")
                .body(
                    mapOf(
                        "mrkt_tp" to "000",
                        "sort_tp" to "1",          // 1:상승률
                        "trde_qty_cnd" to "0000",
                        "stk_cnd" to "0",
                        "crd_cnd" to "0",
                        "updown_incls" to "0",     // 0:상하한 불포함
                        "pric_cnd" to "0",
                        "trde_prica_cnd" to "0",
                        "stex_tp" to "3",
                    ),
                )
                .retrieve()
                .body(PriceChangeRateResponse::class.java)
                ?: throw IllegalStateException("Price change rate response is null")

            return response.pred_pre_flu_rt_upper.mapIndexed { _, item ->
                LeadingStockSnapshot(
                    stockCode = item.stk_cd,
                    stockName = item.stk_nm,
                    currentPrice = parseKiwoomPrice(item.cur_prc),
                    priceChangeRate = item.flu_rt.toDoubleOrNull() ?: 0.0,
                    tradingValueRank = 0,
                    accumulatedTradingValue = 0,
                )
            }.take(count)
        } catch (e: Exception) {
            log.error("Failed to fetch top price change rate stocks", e)
            return emptyList()
        }
    }

    /** 종목 기본 정보 조회 (ka10001) */
    @Cacheable("stockDetail", key = "#stockCode", unless = "#result == null")
    fun fetchStockDetail(stockCode: String): LeadingStockSnapshot? {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching stock detail for {} from Kiwoom API", stockCode)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/stkinfo")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10001")
                // 후보 stockCode는 랭킹(ka10032)에서 이미 _AL(SOR 통합) 접미사를 달고 들어옴 — 그대로 전달.
                .body(mapOf("stk_cd" to stockCode))
                .retrieve()
                .body(StockDetailResponse::class.java)
                ?: return null

            return LeadingStockSnapshot(
                stockCode = response.stk_cd,
                stockName = response.stk_nm,
                currentPrice = parseKiwoomPrice(response.cur_prc),
                priceChangeRate = response.flu_rt.toDoubleOrNull() ?: 0.0,
                tradingValueRank = 0,
                accumulatedTradingValue = 0,
                marketCap = response.mac.toLongOrNull() ?: 0,
                openingPrice = parseKiwoomPrice(response.open_pric),
                previousClose = parseKiwoomPrice(response.base_pric),
                highPrice = parseKiwoomPrice(response.high_pric),
                lowPrice = parseKiwoomPrice(response.low_pric),
            )
        } catch (e: Exception) {
            log.error("Failed to fetch stock detail for {}", stockCode, e)
            return null
        }
    }

    /** 일봉 차트 조회 (ka10081) — _AL 접미사로 SOR 통합 시세, base_dt 기준 과거 봉 N개 반환 */
    @Cacheable("dailyCandles", unless = "#result.isEmpty()")
    fun fetchDailyCandles(stockCode: String, count: Int = 60, baseDate: LocalDate = LocalDate.now()): List<DailyCandle> {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching {} daily candles for stock {} (base {})", count, stockCode, baseDate)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/chart")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10081")
                .body(
                    mapOf(
                        // ka10081은 ka10001과 동일하게 _AL 시 빈 응답 가능성 — KRX 기본 stk_cd로 호출
                        "stk_cd" to stockCode,
                        "base_dt" to baseDate.toString().replace("-", ""),
                        "upd_stkpc_tp" to "1",
                    ),
                )
                .retrieve()
                .body(DailyCandlesResponse::class.java)
                ?: return emptyList()

            val items = response.stk_dt_pole_chart_qry ?: return emptyList()
            val dateFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
            return items.take(count).mapNotNull { candle ->
                // dt가 비어있거나 파싱 실패한 항목은 skip — ka10081 응답 끝쪽에 빈 패딩 항목 가능
                val date = candle.dt.trim().takeIf { it.isNotBlank() }
                    ?.let { runCatching { LocalDate.parse(it, dateFmt) }.getOrNull() }
                    ?: return@mapNotNull null
                val close = parseKiwoomPrice(candle.cur_prc)
                // pred_pre는 부호 포함 정수 (그날 종가 - 전일종가). 전일종가 기준으로 등락률 계산.
                val predPre = candle.pred_pre.trim().toLongOrNull() ?: 0L
                val prevClose = close - predPre
                val changeRate = if (prevClose > 0) predPre.toDouble() / prevClose * 100.0 else 0.0
                DailyCandle(
                    date = date,
                    openPrice = parseKiwoomPrice(candle.open_pric),
                    highPrice = parseKiwoomPrice(candle.high_pric),
                    lowPrice = parseKiwoomPrice(candle.low_pric),
                    closePrice = close,
                    volume = parseKiwoomPrice(candle.trde_qty),
                    changeRate = changeRate,
                )
            }
        } catch (e: Exception) {
            log.error("Failed to fetch daily candles for {}", stockCode, e)
            return emptyList()
        }
    }

    /** 당일 분봉 (ka10080, _AL SOR, 1분봉) — 형성 중이라 짧은 캐시(30s). */
    @Cacheable("minuteCandles", unless = "#result.isEmpty()")
    fun fetchMinuteCandles(stockCode: String): List<MinuteCandle> =
        fetchMinuteCandlesRaw(stockCode, LocalDate.now())

    /** 과거 거래일 분봉 — base_dt로 특정 세션 조회. 데이터 불변이라 당일과 분리해 장기 캐시. */
    @Cacheable("minuteCandlesHistory", unless = "#result.isEmpty()")
    fun fetchHistoricalMinuteCandles(stockCode: String, baseDate: LocalDate): List<MinuteCandle> =
        fetchMinuteCandlesRaw(stockCode, baseDate)

    /** 접미사가 없는 코드에 SOR 통합(_AL)을 붙인다. 이미 _AL/_NX가 붙어 있으면 그대로 둔다. */
    private fun String.withSorSuffix() = if (contains('_')) this else "${this}_AL"

    private fun fetchMinuteCandlesRaw(stockCode: String, baseDate: LocalDate): List<MinuteCandle> {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching minute candles for stock {} (base {})", stockCode, baseDate)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/chart")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10080")
                .body(
                    mapOf(
                        // KRX 기본 코드는 정규장 봉만 준다. SOR 통합(_AL)이라야 NXT 프리·애프터마켓 봉이 함께 온다.
                        // 후보 종목은 랭킹에서 이미 접미사를 달고 오므로, 없을 때만 붙인다.
                        "stk_cd" to stockCode.withSorSuffix(),
                        "tic_scope" to "1",
                        "upd_stkpc_tp" to "1",
                        "base_dt" to baseDate.toString().replace("-", ""),
                    ),
                )
                .retrieve()
                .body(MinuteCandlesResponse::class.java)
                ?: return emptyList()

            val items = response.stk_min_pole_chart_qry ?: return emptyList()
            val dateTimeFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            return items.mapNotNull { candle ->
                // cntr_tm이 비어있거나 파싱 실패한 항목은 skip — 응답 끝쪽 빈 패딩 가능
                val dateTime = candle.cntr_tm.trim().takeIf { it.isNotBlank() }
                    ?.let { runCatching { LocalDateTime.parse(it, dateTimeFmt) }.getOrNull() }
                    ?: return@mapNotNull null
                val close = parseKiwoomPrice(candle.cur_prc)
                val volume = parseKiwoomPrice(candle.trde_qty)
                MinuteCandle(
                    dateTime = dateTime,
                    openPrice = parseKiwoomPrice(candle.open_pric),
                    highPrice = parseKiwoomPrice(candle.high_pric),
                    lowPrice = parseKiwoomPrice(candle.low_pric),
                    closePrice = close,
                    volume = volume,
                    // ka10080 응답엔 거래대금 필드 없음 — 종가 × 거래량으로 근사 (단일 분봉이라 가격 변동 작아 충분)
                    tradingValue = close * volume,
                )
            }
        } catch (e: Exception) {
            log.error("Failed to fetch minute candles for {}", stockCode, e)
            return emptyList()
        }
    }

    // --- Response DTOs ---

    data class TradingVolumeResponse(
        val trde_prica_upper: List<TradingVolumeItem>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class TradingVolumeItem(
        val stk_cd: String,
        val now_rank: String,
        val pred_rank: String,
        val stk_nm: String,
        val cur_prc: String,
        val pred_pre_sig: String,
        val pred_pre: String,
        val flu_rt: String,
        val sel_bid: String,
        val buy_bid: String,
        val now_trde_qty: String,
        val pred_trde_qty: String,
        val trde_prica: String,
    )

    data class PriceChangeRateResponse(
        val pred_pre_flu_rt_upper: List<PriceChangeRateItem>,
    )

    data class PriceChangeRateItem(
        val stk_cls: String,
        val stk_cd: String,
        val stk_nm: String,
        val cur_prc: String,
        val pred_pre_sig: String,
        val pred_pre: String,
        val flu_rt: String,
        val sel_req: String,
        val buy_req: String,
        val now_trde_qty: String,
        val cntr_str: String,
        val cnt: String,
    )

    data class StockDetailResponse(
        val stk_cd: String,
        val stk_nm: String,
        val cur_prc: String,
        val flu_rt: String,
        val mac: String,
        val open_pric: String,
        val base_pric: String,
        val high_pric: String,
        val low_pric: String,
    )

    data class DailyCandlesResponse(
        val stk_dt_pole_chart_qry: List<DailyCandleItem>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class DailyCandleItem(
        val dt: String,             // YYYYMMDD
        val open_pric: String,
        val high_pric: String,
        val low_pric: String,
        val cur_prc: String,        // 종가 (부호 포함, parseKiwoomPrice에서 절대값으로)
        val trde_qty: String,
        val pred_pre: String,       // 부호 포함 (그날 종가 - 전일종가)
    )

    data class MinuteCandlesResponse(
        val stk_min_pole_chart_qry: List<MinuteCandleItem>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class MinuteCandleItem(
        val cntr_tm: String,        // YYYYMMDDHHmmss
        val open_pric: String,
        val high_pric: String,
        val low_pric: String,
        val cur_prc: String,        // 종가 (부호 포함)
        val trde_qty: String,
    )
}
