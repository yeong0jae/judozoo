package judozoo.platform.kis

import judozoo.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Component
class KisFutures(private val client: KisClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class NearMonth(val iscd: String, val name: String, val remainingDays: Int)

    data class FuturesBar(
        val date: String,
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    data class FuturesSummary(
        val name: String,
        val futuresPrice: Double,
        val changeRate: Double,
        val spot: Double,
        val basis: Double,
        val dprt: Double,
        val openInterest: Long,
        val openInterestChange: Long,
    )

    data class FuturesDaily(val summary: FuturesSummary, val candles: List<FuturesBar>)

    data class FuturesPrice(
        val name: String,
        val price: Double,
        val prevClose: Double,
        val priceChange: Double,
        val changeRate: Double,
        val open: Double,
        val high: Double,
        val low: Double,
        val volume: Long,
        val openInterest: Long,
        val openInterestChange: Long,
    )

    data class FuturesInvestors(
        val foreign: Long,
        val individual: Long,
        val institution: Long,
        val securities: Long,
        val insurance: Long,
        val merchantBank: Long,
        val trust: Long,
        val privateEquity: Long,
        val fund: Long,
        val bank: Long,
        val otherOrg: Long,
        val otherCorp: Long,
    )

    fun fetchNearMonth(market: Market): NearMonth? = guarded("선물 근월물 조회", null) {
        val body = okBody(
            BOARD_URL, "FHPIF05030200", "선물 전광판",
            mapOf("FID_COND_MRKT_DIV_CODE" to "F", "FID_COND_SCR_DIV_CODE" to "20503", "FID_COND_MRKT_CLS_CODE" to FUTURES_CLS_CODE.getValue(market)),
        ) ?: return@guarded null
        body["output"]?.values().orEmpty()
            .mapNotNull { row ->
                val iscd = row.text("futs_shrn_iscd")
                val days = row.text("hts_rmnn_dynu").toIntOrNull()
                if (iscd.isEmpty() || days == null || days < 0) null else NearMonth(iscd, row.text("hts_kor_isnm").ifEmpty { iscd }, days)
            }
            .minByOrNull { it.remainingDays }
    }

    fun fetchDaily(iscd: String, from: LocalDate, to: LocalDate, market: String = DAY): FuturesDaily? = guarded("선물 일봉 조회 iscd=$iscd", null) {
        val body = okBody(
            DAILY_URL, "FHKIF03020100", "선물 일봉 (iscd=$iscd)",
            mapOf(
                "FID_COND_MRKT_DIV_CODE" to market, "FID_INPUT_ISCD" to iscd,
                "FID_INPUT_DATE_1" to from.format(YMD), "FID_INPUT_DATE_2" to to.format(YMD), "FID_PERIOD_DIV_CODE" to "D",
            ),
        ) ?: return@guarded null
        val o = body["output1"]?.takeIf { it.isObject && !it.isEmpty } ?: return@guarded null
        val futuresPrice = o.doubleOrNull("futs_prpr") ?: return@guarded null
        val spot = o.doubleOrNull("kospi200_nmix") ?: return@guarded null

        val summary = FuturesSummary(
            name = o.text("hts_kor_isnm"),
            futuresPrice = futuresPrice,
            changeRate = signed(o.text("futs_prdy_ctrt"), o.text("prdy_vrss_sign")),
            spot = spot,
            basis = futuresPrice - spot,
            dprt = o.doubleOrNull("dprt") ?: 0.0,
            openInterest = o.long("hts_otst_stpl_qty"),
            openInterestChange = o.long("otst_stpl_qty_icdc"),
        )
        FuturesDaily(summary, body["output2"]?.values().orEmpty().mapNotNull(::dailyBar).sortedBy { it.date + it.time })
    }

    fun fetchMinute(iscd: String, on: LocalDate, hour: LocalTime, market: String = DAY): List<FuturesBar> =
        fetchMinute(iscd, on, hour.format(HMS), market)

    fun fetchMinute(iscd: String, on: LocalDate, hhmmss: String, market: String = DAY): List<FuturesBar> =
        guarded("선물 분봉 조회 iscd=$iscd market=$market", emptyList()) {
            val body = okBody(
                MINUTE_URL, "FHKIF03020200", "선물 분봉 (iscd=$iscd, market=$market)",
                mapOf(
                    "FID_COND_MRKT_DIV_CODE" to market, "FID_INPUT_ISCD" to iscd,
                    "FID_HOUR_CLS_CODE" to "60",
                    "FID_PW_DATA_INCU_YN" to "N",
                    "FID_FAKE_TICK_INCU_YN" to "N",
                    "FID_INPUT_DATE_1" to on.format(YMD), "FID_INPUT_HOUR_1" to hhmmss,
                ),
            ) ?: return@guarded emptyList()
            body["output2"]?.values().orEmpty().mapNotNull(::minuteBar).sortedBy { it.date + it.time }
        }

    fun fetchPrice(iscd: String, market: String): FuturesPrice? = guarded("선물 시세 조회 iscd=$iscd market=$market", null) {
        val body = okBody(
            PRICE_URL, "FHMIF10000000", "선물 시세 (iscd=$iscd, market=$market)",
            mapOf("FID_COND_MRKT_DIV_CODE" to market, "FID_INPUT_ISCD" to iscd),
        ) ?: return@guarded null
        val o = body["output1"]?.takeIf { it.isObject && !it.isEmpty } ?: return@guarded null
        val price = o.doubleOrNull("futs_prpr") ?: return@guarded null

        val diff = signed(o.text("futs_prdy_vrss"), o.text("prdy_vrss_sign"))
        FuturesPrice(
            name = o.text("hts_kor_isnm"),
            price = price,
            prevClose = price - diff,
            priceChange = diff,
            changeRate = signed(o.text("futs_prdy_ctrt"), o.text("prdy_vrss_sign")),
            open = o.doubleOrNull("futs_oprc") ?: price,
            high = o.doubleOrNull("futs_hgpr") ?: price,
            low = o.doubleOrNull("futs_lwpr") ?: price,
            volume = o.long("acml_vol"),
            openInterest = o.long("hts_otst_stpl_qty"),
            openInterestChange = o.long("otst_stpl_qty_icdc"),
        )
    }

    fun fetchInvestors(market: Market): FuturesInvestors? = guarded("선물 투자자 조회", null) {
        val body = okBody(
            INVESTOR_URL, "FHPTJ04030000", "선물 투자자",
            mapOf("fid_input_iscd" to INVESTOR_ISCD.getValue(market), "fid_input_iscd_2" to INVESTOR_ISCD2.getValue(market)),
        ) ?: return@guarded null
        val o = body["output"]?.values()?.firstOrNull() ?: return@guarded null
        FuturesInvestors(
            foreign = o.long("frgn_ntby_qty"),
            individual = o.long("prsn_ntby_qty"),
            institution = o.long("orgn_ntby_qty"),
            securities = o.long("scrt_ntby_qty"),
            insurance = o.long("insu_ntby_qty"),
            merchantBank = o.long("mrbn_ntby_qty"),
            trust = o.long("ivtr_ntby_qty"),
            privateEquity = o.long("pe_fund_ntby_vol"),
            fund = o.long("fund_ntby_qty"),
            bank = o.long("bank_ntby_qty"),
            otherOrg = o.long("etc_orgt_ntby_vol"),
            otherCorp = o.long("etc_corp_ntby_vol"),
        )
    }

    private fun okBody(url: String, trId: String, label: String, params: Map<String, String>): JsonNode? {
        val response = client.get(url, trId, params)
        if (response.status !in 200..299) throw IllegalStateException("KIS $label HTTP ${response.status}")
        if (!response.ok) {
            log.error("KIS {} 오류 code={} msg={}", label, response.msgCd, response.msg1)
            return null
        }
        return response.body
    }

    private inline fun <T> guarded(label: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        log.error("KIS {} 실패", label, e)
        fallback
    }

    private fun dailyBar(item: JsonNode): FuturesBar? {
        val ymd = item.text("stck_bsop_date")
        val close = item.doubleOrNull("futs_prpr")
        if (ymd.length != 8 || close == null) return null
        return FuturesBar(
            date = "${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6, 8)}",
            time = "00:00:00",
            open = item.doubleOrNull("futs_oprc") ?: close,
            high = item.doubleOrNull("futs_hgpr") ?: close,
            low = item.doubleOrNull("futs_lwpr") ?: close,
            close = close,
            volume = item.doubleOrNull("acml_vol") ?: 0.0,
        )
    }

    private fun minuteBar(item: JsonNode): FuturesBar? {
        val ymd = item.text("stck_bsop_date")
        if (ymd.length != 8) return null
        val hms = item.text("stck_cntg_hour").ifEmpty { "000000" }.padStart(6, '0')
        val close = item.doubleOrNull("futs_prpr") ?: return null
        val hour = hms.substring(0, 2).toIntOrNull() ?: return null
        var on = runCatching { LocalDate.parse(ymd, YMD) }.getOrNull() ?: return null
        if (hour >= 24) on = on.plusDays(1)
        return FuturesBar(
            date = on.toString(),
            time = "%02d:%s:%s".format(hour % 24, hms.substring(2, 4), hms.substring(4, 6)),
            open = item.doubleOrNull("futs_oprc") ?: close,
            high = item.doubleOrNull("futs_hgpr") ?: close,
            low = item.doubleOrNull("futs_lwpr") ?: close,
            close = close,
            volume = item.doubleOrNull("cntg_vol") ?: 0.0,
        )
    }

    companion object {
        const val DAY = "F"

        const val NIGHT = "CM"

        private const val BOARD_URL = "/uapi/domestic-futureoption/v1/quotations/display-board-futures"
        private const val DAILY_URL = "/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice"
        private const val MINUTE_URL = "/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice"
        private const val PRICE_URL = "/uapi/domestic-futureoption/v1/quotations/inquire-price"
        private const val INVESTOR_URL = "/uapi/domestic-stock/v1/quotations/inquire-investor-time-by-market"

        private val FUTURES_CLS_CODE = mapOf(Market.KOSPI to "", Market.KOSDAQ to "KQI")
        private val INVESTOR_ISCD = mapOf(Market.KOSPI to "K2I", Market.KOSDAQ to "KQI")
        private val INVESTOR_ISCD2 = mapOf(Market.KOSPI to "F001", Market.KOSDAQ to "F002")

        private val NEGATIVE_SIGNS = setOf("4", "5")

        private val YMD = DateTimeFormatter.BASIC_ISO_DATE
        private val HMS = DateTimeFormatter.ofPattern("HHmmss")

        private fun signed(magnitude: String, sign: String): Double {
            val value = abs(magnitude.toDoubleOrNull() ?: 0.0)
            return if (sign in NEGATIVE_SIGNS) -value else value
        }

        private fun JsonNode.text(field: String) = get(field)?.takeUnless { it.isNull }?.asString()?.trim().orEmpty()
        private fun JsonNode.doubleOrNull(field: String) = text(field).toDoubleOrNull()
        private fun JsonNode.long(field: String) = text(field).toLongOrNull() ?: 0L
    }
}
