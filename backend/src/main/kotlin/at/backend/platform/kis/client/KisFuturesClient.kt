package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 국내 지수선물(코스피200) 시세 — KIS 국내선물옵션. 종가베팅 지수 상세용.
 * - 근월물 코드: 전광판_선물(FHPIF05030200)에서 잔존일수 최소 종목.
 * - 일봉+요약: 기간별시세(FHKIF03020100) output1(선물·현물·미결제·괴리율) + output2(일봉 OHLC).
 * - 분봉: 분봉조회(FHKIF03020200) output2.
 * 실패·응답오류·파싱불가 시 null/빈리스트(호출측이 스킵하게).
 */
@Component
class KisFuturesClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val hourFmt = DateTimeFormatter.ofPattern("HHmmss")

    /** 코스피200 선물 근월물(잔존일수 최소, 만기 지난 것 제외). 없으면 null. */
    fun fetchNearMonth(): NearMonth? {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-futureoption/v1/quotations/display-board-futures")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "F")
                        .queryParam("FID_COND_SCR_DIV_CODE", "20503")
                        .queryParam("FID_COND_MRKT_CLS_CODE", "") // 공백: KOSPI200
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "FHPIF05030200")
                .header("custtype", "P")
                .retrieve()
                .body(BoardResponse::class.java)
                ?: return null
            if (response.rt_cd != "0") {
                log.error("KIS 선물 전광판 오류: code={}, msg={}", response.msg_cd, response.msg1)
                return null
            }
            return response.output.orEmpty()
                .mapNotNull { row ->
                    val iscd = row.futs_shrn_iscd?.trim() ?: return@mapNotNull null
                    val days = row.hts_rmnn_dynu?.trim()?.toIntOrNull() ?: return@mapNotNull null
                    if (days < 0) null else NearMonth(iscd, row.hts_kor_isnm?.trim() ?: iscd, days)
                }
                .minByOrNull { it.rmnnDays }
        } catch (e: Exception) {
            log.error("KIS 선물 근월물 조회 실패", e)
            return null
        }
    }

    /** [iscd] 근월물의 기간별(일봉) 시세 — 요약(현재 선물·현물·미결제·괴리율) + 일봉 OHLC. */
    fun fetchDaily(iscd: String, from: LocalDate, to: LocalDate): FuturesDaily? {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "F")
                        .queryParam("FID_INPUT_ISCD", iscd)
                        .queryParam("FID_INPUT_DATE_1", from.format(dateFmt))
                        .queryParam("FID_INPUT_DATE_2", to.format(dateFmt))
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "FHKIF03020100")
                .header("custtype", "P")
                .retrieve()
                .body(DailyResponse::class.java)
                ?: return null
            if (response.rt_cd != "0") {
                log.error("KIS 선물 일봉 오류: code={}, msg={} (iscd={})", response.msg_cd, response.msg1, iscd)
                return null
            }
            val o = response.output1 ?: return null
            val futsPrice = o.futs_prpr?.trim()?.toDoubleOrNull() ?: return null
            val spot = o.kospi200_nmix?.trim()?.toDoubleOrNull() ?: return null
            val summary = FuturesSummary(
                name = o.hts_kor_isnm?.trim().orEmpty(),
                futuresPrice = futsPrice,
                changeRate = signed(o.futs_prdy_ctrt, o.prdy_vrss_sign),
                spot = spot,
                spotChangeRate = spotChangeRate(o, spot),
                basis = o.basis?.trim()?.toDoubleOrNull() ?: (futsPrice - spot),
                dprt = o.dprt?.trim()?.toDoubleOrNull() ?: 0.0,
                openInterest = o.hts_otst_stpl_qty?.trim()?.toLongOrNull() ?: 0L,
                openInterestChange = o.otst_stpl_qty_icdc?.trim()?.toLongOrNull() ?: 0L,
            )
            val candles = response.output2.orEmpty().mapNotNull { it.toBar(minute = false) }.sortedBy { it.date + it.time }
            return FuturesDaily(summary, candles)
        } catch (e: Exception) {
            log.error("KIS 선물 일봉 조회 실패 (iscd={})", iscd, e)
            return null
        }
    }

    /** [iscd] 근월물 1분봉 — [date] [hour] 기준 이전 102봉(당일). 시각 오름차순. */
    fun fetchMinute(iscd: String, date: LocalDate, hour: LocalTime): List<FuturesBar> {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "F")
                        .queryParam("FID_INPUT_ISCD", iscd)
                        .queryParam("FID_HOUR_CLS_CODE", "60") // 1분
                        .queryParam("FID_PW_DATA_INCU_YN", "N") // 당일
                        .queryParam("FID_FAKE_TICK_INCU_YN", "N")
                        .queryParam("FID_INPUT_DATE_1", date.format(dateFmt))
                        .queryParam("FID_INPUT_HOUR_1", hour.format(hourFmt))
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "FHKIF03020200")
                .header("custtype", "P")
                .retrieve()
                .body(MinuteResponse::class.java)
                ?: return emptyList()
            if (response.rt_cd != "0") {
                log.error("KIS 선물 분봉 오류: code={}, msg={} (iscd={})", response.msg_cd, response.msg1, iscd)
                return emptyList()
            }
            return response.output2.orEmpty().mapNotNull { it.toBar(minute = true) }.sortedBy { it.date + it.time }
        } catch (e: Exception) {
            log.error("KIS 선물 분봉 조회 실패 (iscd={})", iscd, e)
            return emptyList()
        }
    }

    /** 등락률 크기(ctrt)에 부호코드(1상한2상승3보합4하한5하락)를 적용해 부호 포함 %로. */
    private fun signed(ctrt: String?, sign: String?): Double {
        val mag = abs(ctrt?.trim()?.toDoubleOrNull() ?: 0.0)
        return if (sign?.trim() in setOf("4", "5")) -mag else mag
    }

    /** KOSPI200 등락률 — ctrt가 0으로 오는 경우가 있어, 전일 대비 포인트에서 역산해 보완. */
    private fun spotChangeRate(o: DailySummary, spot: Double): Double {
        val ctrt = signed(o.kospi200_prdy_ctrt, o.kospi200_prdy_vrss_sign)
        if (ctrt != 0.0) return ctrt
        val diff = signed(o.kospi200_prdy_vrss, o.kospi200_prdy_vrss_sign)
        val prevClose = spot - diff
        return if (diff == 0.0 || prevClose <= 0.0) 0.0 else diff / prevClose * 100
    }

    // ── 응답 DTO (필요 필드만) ──
    data class BoardResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output: List<BoardRow>? = null, // 전광판_선물은 응답 키가 output(단수)
    )

    data class BoardRow(
        val futs_shrn_iscd: String? = null,
        val hts_kor_isnm: String? = null,
        val hts_rmnn_dynu: String? = null,
    )

    data class DailyResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output1: DailySummary? = null,
        val output2: List<DailyBar>? = null,
    )

    data class DailySummary(
        val hts_kor_isnm: String? = null,
        val futs_prpr: String? = null,
        val futs_prdy_ctrt: String? = null,
        val prdy_vrss_sign: String? = null,
        val basis: String? = null,
        val kospi200_nmix: String? = null,
        val kospi200_prdy_vrss: String? = null,
        val kospi200_prdy_ctrt: String? = null,
        val kospi200_prdy_vrss_sign: String? = null,
        val hts_otst_stpl_qty: String? = null,
        val otst_stpl_qty_icdc: String? = null,
        val dprt: String? = null,
    )

    data class DailyBar(
        val stck_bsop_date: String? = null,
        val futs_prpr: String? = null,
        val futs_oprc: String? = null,
        val futs_hgpr: String? = null,
        val futs_lwpr: String? = null,
        val acml_vol: String? = null,
    ) {
        fun toBar(minute: Boolean): FuturesBar? {
            val date = stck_bsop_date?.trim()?.takeIf { it.length == 8 } ?: return null
            val close = futs_prpr?.trim()?.toDoubleOrNull() ?: return null
            return FuturesBar(
                date = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}",
                time = "00:00:00",
                open = futs_oprc?.trim()?.toDoubleOrNull() ?: close,
                high = futs_hgpr?.trim()?.toDoubleOrNull() ?: close,
                low = futs_lwpr?.trim()?.toDoubleOrNull() ?: close,
                close = close,
                volume = acml_vol?.trim()?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    data class MinuteResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output2: List<MinuteBar>? = null,
    )

    data class MinuteBar(
        val stck_bsop_date: String? = null,
        val stck_cntg_hour: String? = null,
        val futs_prpr: String? = null,
        val futs_oprc: String? = null,
        val futs_hgpr: String? = null,
        val futs_lwpr: String? = null,
        val cntg_vol: String? = null,
    ) {
        fun toBar(minute: Boolean): FuturesBar? {
            val date = stck_bsop_date?.trim()?.takeIf { it.length == 8 } ?: return null
            val hms = (stck_cntg_hour?.trim() ?: "000000").padStart(6, '0')
            val close = futs_prpr?.trim()?.toDoubleOrNull() ?: return null
            return FuturesBar(
                date = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}",
                time = "${hms.substring(0, 2)}:${hms.substring(2, 4)}:${hms.substring(4, 6)}",
                open = futs_oprc?.trim()?.toDoubleOrNull() ?: close,
                high = futs_hgpr?.trim()?.toDoubleOrNull() ?: close,
                low = futs_lwpr?.trim()?.toDoubleOrNull() ?: close,
                close = close,
                volume = cntg_vol?.trim()?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    // ── 결과 타입 ──
    data class NearMonth(val iscd: String, val name: String, val rmnnDays: Int)

    data class FuturesSummary(
        val name: String,
        val futuresPrice: Double,
        val changeRate: Double,
        val spot: Double,
        val spotChangeRate: Double,
        val basis: Double, // KIS 제공 베이시스(없으면 선물 − 현물)
        val dprt: Double,
        val openInterest: Long,
        val openInterestChange: Long,
    )

    data class FuturesDaily(val summary: FuturesSummary, val candles: List<FuturesBar>)

    data class FuturesBar(
        val date: String, // yyyy-MM-dd
        val time: String, // HH:mm:ss (1d는 00:00:00)
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )
}
