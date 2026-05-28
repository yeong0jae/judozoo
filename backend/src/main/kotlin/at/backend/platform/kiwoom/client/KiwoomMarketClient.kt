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
    fun fetchTopTradingValueStocks(count: Int = 50): List<LeadingStockSnapshot> {
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
    fun fetchStockDetail(stockCode: String): LeadingStockSnapshot? {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching stock detail for {} from Kiwoom API", stockCode)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/stkinfo")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10001")
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

    /** 일별 주가 조회 (ka10086) */
    fun fetchDailyCandles(stockCode: String, count: Int = 60): List<DailyCandle> {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching {} daily candles for stock {}", count, stockCode)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/mrkcond")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10086")
                .body(
                    mapOf(
                        "stk_cd" to stockCode,
                        "qry_dt" to LocalDate.now().toString().replace("-", ""),
                        "indc_tp" to "0",
                    ),
                )
                .retrieve()
                .body(DailyCandlesResponse::class.java)
                ?: return emptyList()

            return response.daly_stkpc.take(count).map { candle ->
                DailyCandle(
                    date = LocalDate.parse(candle.date, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
                    openPrice = parseKiwoomPrice(candle.open_pric),
                    highPrice = parseKiwoomPrice(candle.high_pric),
                    lowPrice = parseKiwoomPrice(candle.low_pric),
                    closePrice = parseKiwoomPrice(candle.close_pric),
                    volume = parseKiwoomPrice(candle.trde_qty),
                    changeRate = candle.flu_rt.toDoubleOrNull() ?: 0.0,
                )
            }
        } catch (e: Exception) {
            log.error("Failed to fetch daily candles for {}", stockCode, e)
            return emptyList()
        }
    }

    /** 분봉 조회 (trading 코드 기준 — 키움 신 OpenAPI path는 추후 확인 필요할 수 있음) */
    fun fetchMinuteCandles(stockCode: String): List<MinuteCandle> {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching minute candles for stock {}", stockCode)

            val response = kiwoomRestClient.get()
                .uri { builder ->
                    builder.path("/v1/stock/{stockCode}/candles/minute")
                        .queryParam("count", 60)
                        .build(stockCode)
                }
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .retrieve()
                .body(MinuteCandlesResponse::class.java)
                ?: return emptyList()

            return response.candles.map { candle ->
                MinuteCandle(
                    dateTime = LocalDateTime.parse(candle.dateTime),
                    openPrice = candle.openPrice,
                    highPrice = candle.highPrice,
                    lowPrice = candle.lowPrice,
                    closePrice = candle.closePrice,
                    volume = candle.volume,
                    tradingValue = candle.tradingValue,
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

    data class DailyCandlesResponse(val daly_stkpc: List<DailyCandleItem>)

    data class DailyCandleItem(
        val date: String,
        val open_pric: String,
        val high_pric: String,
        val low_pric: String,
        val close_pric: String,
        val trde_qty: String,
        val flu_rt: String,
    )

    data class MinuteCandlesResponse(val candles: List<MinuteCandleItem>)

    data class MinuteCandleItem(
        val dateTime: String,
        val openPrice: Long,
        val highPrice: Long,
        val lowPrice: Long,
        val closePrice: Long,
        val volume: Long,
        val tradingValue: Long,
    )
}
