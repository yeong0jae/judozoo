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

/**
 * 키움 시세 — 거래대금/등락률 상위, 종목 기본정보, 일봉·분봉.
 *
 * **함정 두 가지가 여기 모여 있다.**
 * 1. `cur_prc` 등 가격 필드의 앞 부호는 등락 **방향 표식**이지 음수가 아니다. 절대값으로 읽는다([parsePrice]).
 * 2. KRX 기본 코드는 정규장만 준다. 프리·애프터마켓이 필요한 TR은 SOR 통합(`_AL`) 코드라야 한다.
 *    단 일봉(ka10081)은 `_AL`이면 빈 응답이 오는 경우가 있어 **기본 코드로 부른다**.
 *
 * Python과 다른 점 하나: 종목 기본정보가 `return_code` ≠ 0이면 Python은 빈 값으로 채운 스냅샷을, 여기서는 null을 준다.
 */
@Component
class KiwoomMarket(private val client: KiwoomClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val nxtListed = TtlCache<String, Boolean?>("nxtListed", Duration.ofHours(24), maxSize = 200, skipIf = { it == null })

    /**
     * ka10081 한 페이지(600봉, 최신순)를 통째로 든다. **키는 키움에 보내는 값(종목·기준일)뿐이다** —
     * 개수는 받은 뒤 자르므로, 개수를 키에 넣으면 필터용(60)과 차트(200)가 같은 요청을 두 번 보낸다.
     */
    private val dailyPages = TtlCache<Pair<String, LocalDate>, List<DailyCandle>>(
        "dailyCandles", Duration.ofSeconds(60), maxSize = 60, skipIf = { it.isEmpty() },
    )

    /**
     * 거래대금 상위 (ka10032).
     *
     * **캐시하지 않는다** — 언제까지 들고 있을지가 장 상태에 달려 있어 `leadingstock`이 캐시를 쥔다.
     * 빈 응답·오류는 예외로 올린다. 빈 결과를 캐싱하면 후속 폴링이 TTL 동안 빈 목록을 돌려준다.
     */
    fun fetchTopTradingValueStocks(count: Int = 50): List<LeadingStockSnapshot> {
        log.info("키움 거래대금 상위 {}건 조회", count)
        val response = client.query(
            RANK_URL, "ka10032",
            mapOf("mrkt_tp" to "000", "mang_stk_incls" to "0", "stex_tp" to "3"), // 전체 · 관리종목 미포함 · KRX+NXT 통합
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
                accumulatedTradingValue = toLong(item.textOrNull("trde_prica")) * 1_000_000, // 백만원 단위
                accumulatedVolume = optionalLong(item.textOrNull("now_trde_qty")),
                limitUp = item.textOrNull("pred_pre_sig") == SIG_LIMIT_UP,
            )
        }
    }

    /** 등락률 상위 (ka10027). 실패해도 빈 목록 — 보조 지표라 호출부를 막지 않는다. */
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

    /**
     * 종목 기본 정보 (ka10001).
     *
     * KRX 기본 코드는 정규장 종가에 멈춘다 — SOR 통합(`_AL`)이라야 NXT 프리·애프터 체결이 반영된다.
     * 후보 코드는 랭킹에서 이미 접미사를 달고 오므로 없을 때만 붙인다.
     */
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

    /**
     * NXT 상장 여부 (ka10001 `_NX`). 실패하면 null — 상장이라고도 아니라고도 단정하지 않는다.
     *
     * 비상장 종목은 `return_code` 0에 **종목명·가격이 빈 값**으로 온다(027 0단계). 오류 코드가 아니다.
     * 랭킹 코드는 비상장도 `_AL`이 붙어 와서 그쪽으로는 가릴 수 없다.
     */
    fun fetchNxtListed(stockCode: String): Boolean? = nxtListed.get(stockCode) {
        try {
            val body = client.query(STOCK_INFO_URL, "ka10001", mapOf("stk_cd" to "${baseCode(stockCode)}_NX")).body
            body.text("stk_nm").isNotBlank()
        } catch (e: Exception) {
            log.error("키움 NXT 상장 조회 실패 stk_cd={}", stockCode, e)
            null
        }
    }

    /** 일봉 (ka10081) — 기준일부터 과거 봉 [count]개. */
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
            // 응답 끝쪽에 빈 패딩 항목이 올 수 있다 — 파싱 실패는 건너뛴다
            val date = parseDate(item.text("dt")) ?: return@mapNotNull null
            val close = parsePrice(item.text("cur_prc"))
            // pred_pre는 부호 포함 정수(그날 종가 - 전일종가). 전일종가 기준으로 등락률을 만든다
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

    /**
     * 과거 거래일 분봉 (ka10080) — 기준일부터 거꾸로 900봉 한 페이지.
     *
     * **캐시하지 않는다.** 받은 페이지의 완성된 날은 분봉 보관소가 날짜 단위로 보관한다.
     * KRX 기본 코드는 정규장 봉만 준다 — SOR 통합(`_AL`)이라야 NXT 프리·애프터 봉이 함께 온다.
     */
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
                // ka10080 응답엔 거래대금 필드가 없다 — 종가 × 거래량으로 근사한다(단일 분봉이라 충분)
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

        /**
         * 키움 등락부호(`pred_pre_sig`) 중 상한가. 1:상한 2:상승 3:보합 4:하한 5:하락.
         *
         * **등락률로 상한가를 가려내면 안 된다.** 신규상장 종목은 제한폭이 없어 부호가 2(상승)인 채로
         * +150%가 나온다 — 2026-09-22 실측에서 한국제17호스팩이 +153.00%/부호 2였다.
         */
        private const val SIG_LIMIT_UP = "1"

        private val COMPACT_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** 키움 가격 — 앞의 +/- 는 등락 방향 표식이라 떼고 절대값으로 읽는다. */
        fun parsePrice(value: String): Long {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return 0
            return trimmed.removePrefix("+").removePrefix("-").replace("-", "").toLongOrNull() ?: 0
        }

        /** 접미사가 없는 코드에 SOR 통합(`_AL`)을 붙인다. 이미 `_AL`/`_NX`면 그대로 둔다. */
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
