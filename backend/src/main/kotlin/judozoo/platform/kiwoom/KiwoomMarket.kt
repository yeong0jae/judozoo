package judozoo.platform.kiwoom

import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.library.KST
import judozoo.library.TtlCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class KiwoomMarket(private val client: KiwoomClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val nxtListed = TtlCache<String, Boolean?>("nxtListed", Duration.ofHours(24), maxSize = 200, skipIf = { it == null })

    private val dailyPages = TtlCache<Pair<String, LocalDate>, List<DailyCandle>>(
        "dailyCandles", Duration.ofSeconds(60), maxSize = 60, skipIf = { it.isEmpty() },
    )

    fun fetchTopTradingValueStocks(count: Int = 50): List<LeadingStockSnapshot> {
        log.info("키움 거래대금 상위 {}건 조회", count)
        val response = client.query(
            RANK_URL, "ka10032",
            mapOf("mrkt_tp" to "000", "mang_stk_incls" to "0", "stex_tp" to "3"),
        )
        val items = response.body["trde_prica_upper"]
            ?: throw IllegalStateException("Kiwoom trading value ranking returned no list. msg=${response.body["return_msg"]?.asString()}")

        return items.values().take(count).mapIndexed { index, item ->
            LeadingStockSnapshot(
                stockCode = item.text("stk_cd"),
                stockName = item.text("stk_nm"),
                currentPrice = parsePrice(item.text("cur_prc")),
                priceChangeRate = toDouble(item.textOrNull("flu_rt")),
                tradingValueRank = toLong(item.textOrNull("now_rank")).toInt().takeIf { it != 0 } ?: (index + 1),
                accumulatedTradingValue = toLong(item.textOrNull("trde_prica")) * 1_000_000,
                accumulatedVolume = optionalLong(item.textOrNull("now_trde_qty")),
                limitUp = item.textOrNull("pred_pre_sig") == SIG_LIMIT_UP,
            )
        }
    }

    fun fetchTopPriceChangeRateStocks(count: Int = 50): List<LeadingStockSnapshot> = try {
        log.info("키움 등락률 상위 {}건 조회", count)
        val body = client.query(
            RANK_URL, "ka10027",
            mapOf(
                "mrkt_tp" to "000", "sort_tp" to "1", "trde_qty_cnd" to "0000", "stk_cnd" to "0", "crd_cnd" to "0",
                "updown_incls" to "0", "pric_cnd" to "0", "trde_prica_cnd" to "0", "stex_tp" to "3",
            ),
        ).body
        body["pred_pre_flu_rt_upper"]?.values().orEmpty().take(count).map { item ->
            LeadingStockSnapshot(
                stockCode = item.text("stk_cd"),
                stockName = item.text("stk_nm"),
                currentPrice = parsePrice(item.text("cur_prc")),
                priceChangeRate = toDouble(item.textOrNull("flu_rt")),
                tradingValueRank = 0,
                accumulatedTradingValue = 0,
            )
        }
    } catch (e: Exception) {
        log.error("키움 등락률 상위 조회 실패", e)
        emptyList()
    }

    fun fetchStockDetail(stockCode: String): LeadingStockSnapshot? = try {
        log.info("키움 종목 기본정보 조회 stk_cd={}", stockCode)
        val body = client.query(STOCK_INFO_URL, "ka10001", mapOf("stk_cd" to withSorSuffix(stockCode))).body
        LeadingStockSnapshot(
            stockCode = body.text("stk_cd"),
            stockName = body.text("stk_nm"),
            currentPrice = parsePrice(body.text("cur_prc")),
            priceChangeRate = toDouble(body.textOrNull("flu_rt")),
            tradingValueRank = 0,
            accumulatedTradingValue = 0,
            accumulatedVolume = optionalLong(body.textOrNull("trde_qty")),
            marketCap = toLong(body.textOrNull("mac")),
            openingPrice = parsePrice(body.text("open_pric")),
            previousClose = parsePrice(body.text("base_pric")),
            highPrice = parsePrice(body.text("high_pric")),
            lowPrice = parsePrice(body.text("low_pric")),
        )
    } catch (e: Exception) {
        log.error("키움 종목 기본정보 조회 실패 stk_cd={}", stockCode, e)
        null
    }

    fun fetchNxtListed(stockCode: String): Boolean? = nxtListed.get(stockCode) {
        try {
            val body = client.query(STOCK_INFO_URL, "ka10001", mapOf("stk_cd" to "${baseCode(stockCode)}_NX")).body
            body.text("stk_nm").isNotBlank()
        } catch (e: Exception) {
            log.error("키움 NXT 상장 조회 실패 stk_cd={}", stockCode, e)
            null
        }
    }

    fun fetchDailyCandles(stockCode: String, count: Int = 60, baseDate: LocalDate? = null): List<DailyCandle> {
        val code = baseCode(stockCode)
        val base = baseDate ?: LocalDate.now(clock.withZone(KST))
        return dailyPages.get(code to base) { fetchDailyPage(code, base) }.take(count)
    }

    private fun fetchDailyPage(stockCode: String, base: LocalDate): List<DailyCandle> = try {
        log.info("키움 일봉 조회 stk_cd={} base={}", stockCode, base)
        val body = client.query(
            CHART_URL, "ka10081",
            mapOf("stk_cd" to stockCode, "base_dt" to base.format(DateTimeFormatter.BASIC_ISO_DATE), "upd_stkpc_tp" to "1"),
        ).body
        body["stk_dt_pole_chart_qry"]?.values().orEmpty().mapNotNull { item ->
            val date = parseDate(item.text("dt")) ?: return@mapNotNull null
            val close = parsePrice(item.text("cur_prc"))
            val predPre = toLong(item.textOrNull("pred_pre"))
            val prevClose = close - predPre
            DailyCandle(
                date = date,
                openPrice = parsePrice(item.text("open_pric")),
                highPrice = parsePrice(item.text("high_pric")),
                lowPrice = parsePrice(item.text("low_pric")),
                closePrice = close,
                volume = parsePrice(item.text("trde_qty")),
                changeRate = if (prevClose > 0) predPre.toDouble() / prevClose * 100.0 else 0.0,
            )
        }
    } catch (e: Exception) {
        log.error("키움 일봉 조회 실패 stk_cd={}", stockCode, e)
        emptyList()
    }

    fun fetchHistoricalMinuteCandles(stockCode: String, baseDate: LocalDate): List<MinuteCandle> = try {
        log.info("키움 분봉 조회 stk_cd={} base={}", stockCode, baseDate)
        val body = client.query(
            CHART_URL, "ka10080",
            mapOf(
                "stk_cd" to withSorSuffix(stockCode), "tic_scope" to "1", "upd_stkpc_tp" to "1",
                "base_dt" to baseDate.format(DateTimeFormatter.BASIC_ISO_DATE),
            ),
        ).body
        body["stk_min_pole_chart_qry"]?.values().orEmpty().mapNotNull { item ->
            val at = parseDateTime(item.text("cntr_tm")) ?: return@mapNotNull null
            val close = parsePrice(item.text("cur_prc"))
            val volume = parsePrice(item.text("trde_qty"))
            MinuteCandle(
                dateTime = at,
                openPrice = parsePrice(item.text("open_pric")),
                highPrice = parsePrice(item.text("high_pric")),
                lowPrice = parsePrice(item.text("low_pric")),
                closePrice = close,
                volume = volume,
                tradingValue = close * volume,
            )
        }
    } catch (e: Exception) {
        log.error("키움 분봉 조회 실패 stk_cd={}", stockCode, e)
        emptyList()
    }

    companion object {
        private const val RANK_URL = "/api/dostk/rkinfo"
        private const val STOCK_INFO_URL = "/api/dostk/stkinfo"
        private const val CHART_URL = "/api/dostk/chart"

        private const val SIG_LIMIT_UP = "1"

        private val COMPACT_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        fun parsePrice(value: String): Long {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return 0
            return trimmed.removePrefix("+").removePrefix("-").replace("-", "").toLongOrNull() ?: 0
        }

        private fun withSorSuffix(stockCode: String) = if ("_" in stockCode) stockCode else "${stockCode}_AL"

        private fun baseCode(stockCode: String) = stockCode.substringBefore("_")

        private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()
        private fun JsonNode.text(field: String): String = textOrNull(field).orEmpty()

        private fun toLong(value: String?): Long = value?.trim()?.toLongOrNull() ?: 0
        private fun toDouble(value: String?): Double = value?.trim()?.toDoubleOrNull() ?: 0.0
        private fun optionalLong(value: String?): Long? = if (value.isNullOrBlank()) null else toLong(value)

        private fun parseDate(value: String): LocalDate? =
            value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }

        private fun parseDateTime(value: String): LocalDateTime? =
            value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it, COMPACT_DATE_TIME) }.getOrNull() }
    }
}
