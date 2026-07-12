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

    companion object {
        const val DAY = "F" // 정규장 지수선물
        const val NIGHT = "CM" // 야간선물
    }

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

    /** [iscd] 근월물의 기간별(일봉) 시세 — 요약(현재 선물·현물·미결제·괴리율) + 일봉 OHLC. [market]: F 정규장 / CM 야간. */
    fun fetchDaily(iscd: String, from: LocalDate, to: LocalDate, market: String = DAY): FuturesDaily? {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", market)
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
                // 시장 베이시스 = 선물 − 현물. KIS의 basis 필드는 이론가 − 현물(캐리)이라 쓰지 않는다.
                basis = futsPrice - spot,
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

    /** [iscd] 근월물 1분봉 — [date] [hour](HHMMSS) 기준 이전 102봉. 시각 오름차순. */
    fun fetchMinute(iscd: String, date: LocalDate, hour: LocalTime): List<FuturesBar> =
        fetchMinute(iscd, date, hour.format(hourFmt), DAY)

    /**
     * [iscd] 근월물 1분봉 — [market] 시장구분(F 정규장 / CM 야간).
     * 야간은 자정 이후 시각이 +24시간으로 오고 가므로 [hour]를 HHMMSS 문자열로 받는다(예: 253000 = 01:30).
     */
    fun fetchMinute(iscd: String, date: LocalDate, hour: String, market: String): List<FuturesBar> {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", market)
                        .queryParam("FID_INPUT_ISCD", iscd)
                        .queryParam("FID_HOUR_CLS_CODE", "60") // 1분
                        .queryParam("FID_PW_DATA_INCU_YN", "N") // 당일
                        .queryParam("FID_FAKE_TICK_INCU_YN", "N")
                        .queryParam("FID_INPUT_DATE_1", date.format(dateFmt))
                        .queryParam("FID_INPUT_HOUR_1", hour)
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
                log.error("KIS 선물 분봉 오류: code={}, msg={} (iscd={}, market={})", response.msg_cd, response.msg1, iscd, market)
                return emptyList()
            }
            return response.output2.orEmpty().mapNotNull { it.toBar() }.sortedBy { it.date + it.time }
        } catch (e: Exception) {
            log.error("KIS 선물 분봉 조회 실패 (iscd={}, market={})", iscd, market, e)
            return emptyList()
        }
    }

    /**
     * [iscd] 근월물 시세 — 선물옵션 시세(FHMIF10000000). [market]이 CM이면 야간선물.
     * 야간의 전일 종가(futs_prdy_clpr)는 직전 정규장 종가라, 현재가와의 차이가 곧 갭이다.
     */
    fun fetchPrice(iscd: String, market: String): FuturesPrice? {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-futureoption/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", market)
                        .queryParam("FID_INPUT_ISCD", iscd)
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "FHMIF10000000")
                .header("custtype", "P")
                .retrieve()
                .body(PriceResponse::class.java)
                ?: return null
            if (response.rt_cd != "0") {
                log.error("KIS 선물 시세 오류: code={}, msg={} (iscd={}, market={})", response.msg_cd, response.msg1, iscd, market)
                return null
            }
            val o = response.output1 ?: return null
            val price = o.futs_prpr?.trim()?.toDoubleOrNull() ?: return null
            // futs_prdy_clpr은 야간(CM)에서 정규장 종가가 아닌 값이 온다. 전일 대비로 역산해야 맞는다.
            val diff = signed(o.futs_prdy_vrss, o.prdy_vrss_sign)
            return FuturesPrice(
                name = o.hts_kor_isnm?.trim().orEmpty(),
                price = price,
                prevClose = price - diff,
                priceChange = diff,
                changeRate = signed(o.futs_prdy_ctrt, o.prdy_vrss_sign),
                open = o.futs_oprc?.trim()?.toDoubleOrNull() ?: price,
                high = o.futs_hgpr?.trim()?.toDoubleOrNull() ?: price,
                low = o.futs_lwpr?.trim()?.toDoubleOrNull() ?: price,
                volume = o.acml_vol?.trim()?.toLongOrNull() ?: 0L,
                openInterest = o.hts_otst_stpl_qty?.trim()?.toLongOrNull() ?: 0L,
                openInterestChange = o.otst_stpl_qty_icdc?.trim()?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            log.error("KIS 선물 시세 조회 실패 (iscd={}, market={})", iscd, market, e)
            return null
        }
    }

    /**
     * 선물 시장 투자자별 순매수(장중 시세성) — 외국인·개인·기관계. 단위: 계약.
     * 시장별 투자자매매동향(FHPTJ04030000), 시장구분 K2I + 업종구분 F001(선물).
     */
    fun fetchInvestors(): FuturesInvestors? {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/quotations/inquire-investor-time-by-market")
                        .queryParam("fid_input_iscd", "K2I") // 선물·콜·풋
                        .queryParam("fid_input_iscd_2", "F001") // 선물
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "FHPTJ04030000")
                .header("custtype", "P")
                .retrieve()
                .body(InvestorResponse::class.java)
                ?: return null
            if (response.rt_cd != "0") {
                log.error("KIS 선물 투자자 오류: code={}, msg={}", response.msg_cd, response.msg1)
                return null
            }
            val o = response.output ?: return null
            fun qty(v: String?) = v?.trim()?.toLongOrNull() ?: 0L
            return FuturesInvestors(
                foreign = qty(o.frgn_ntby_qty),
                individual = qty(o.prsn_ntby_qty),
                institution = qty(o.orgn_ntby_qty),
                securities = qty(o.scrt_ntby_qty),
                insurance = qty(o.insu_ntby_qty),
                merchantBank = qty(o.mrbn_ntby_qty),
                trust = qty(o.ivtr_ntby_qty),
                privateEquity = qty(o.pe_fund_ntby_vol),
                fund = qty(o.fund_ntby_qty),
                bank = qty(o.bank_ntby_qty),
                otherOrg = qty(o.etc_orgt_ntby_vol),
                otherCorp = qty(o.etc_corp_ntby_vol),
            )
        } catch (e: Exception) {
            log.error("KIS 선물 투자자 조회 실패", e)
            return null
        }
    }

    /** 등락률 크기(ctrt)에 부호코드(1상한2상승3보합4하한5하락)를 적용해 부호 포함 %로. */
    private fun signed(ctrt: String?, sign: String?): Double {
        val mag = abs(ctrt?.trim()?.toDoubleOrNull() ?: 0.0)
        return if (sign?.trim() in setOf("4", "5")) -mag else mag
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
        val kospi200_nmix: String? = null,
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
        /**
         * 야간선물은 한 세션을 하나의 영업일로 묶으려고 자정 이후 시각을 +24시간으로 보낸다(25:30 = 익일 01:30).
         * 24시 이상이면 날짜를 하루 넘기고 시각에서 24시간을 빼, 프론트가 쓰는 정상 타임스탬프로 되돌린다.
         */
        fun toBar(): FuturesBar? {
            val ymd = stck_bsop_date?.trim()?.takeIf { it.length == 8 } ?: return null
            val hms = (stck_cntg_hour?.trim() ?: "000000").padStart(6, '0')
            val close = futs_prpr?.trim()?.toDoubleOrNull() ?: return null
            val hour = hms.substring(0, 2).toIntOrNull() ?: return null
            val date = LocalDate.parse(ymd, DateTimeFormatter.BASIC_ISO_DATE)
                .let { if (hour >= 24) it.plusDays(1) else it }
            return FuturesBar(
                date = date.toString(),
                time = "%02d:%s:%s".format(hour % 24, hms.substring(2, 4), hms.substring(4, 6)),
                open = futs_oprc?.trim()?.toDoubleOrNull() ?: close,
                high = futs_hgpr?.trim()?.toDoubleOrNull() ?: close,
                low = futs_lwpr?.trim()?.toDoubleOrNull() ?: close,
                close = close,
                volume = cntg_vol?.trim()?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    data class PriceResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output1: PriceRow? = null,
    )

    data class PriceRow(
        val hts_kor_isnm: String? = null,
        val futs_prpr: String? = null,
        val futs_prdy_vrss: String? = null,
        val futs_prdy_ctrt: String? = null,
        val prdy_vrss_sign: String? = null,
        val futs_oprc: String? = null,
        val futs_hgpr: String? = null,
        val futs_lwpr: String? = null,
        val acml_vol: String? = null,
        val hts_otst_stpl_qty: String? = null,
        val otst_stpl_qty_icdc: String? = null,
    )

    data class InvestorResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output: InvestorRow? = null,
    )

    data class InvestorRow(
        val frgn_ntby_qty: String? = null,
        val prsn_ntby_qty: String? = null,
        val orgn_ntby_qty: String? = null,
        // 기관 세부 — 사모펀드·기타단체·기타법인만 필드명이 _vol, 나머지는 _qty
        val scrt_ntby_qty: String? = null,
        val insu_ntby_qty: String? = null,
        val mrbn_ntby_qty: String? = null,
        val ivtr_ntby_qty: String? = null,
        val pe_fund_ntby_vol: String? = null,
        val fund_ntby_qty: String? = null,
        val bank_ntby_qty: String? = null,
        val etc_orgt_ntby_vol: String? = null,
        val etc_corp_ntby_vol: String? = null,
    )

    // ── 결과 타입 ──
    data class NearMonth(val iscd: String, val name: String, val rmnnDays: Int)

    /** 선물 현재가 스냅샷. 야간(CM)이면 priceChange가 직전 정규장 종가 대비 갭. */
    data class FuturesPrice(
        val name: String,
        val price: Double,
        val prevClose: Double, // 전일 대비로 역산 — KIS의 futs_prdy_clpr은 야간에서 못 믿는다
        val priceChange: Double, // 전일 대비(포인트)
        val changeRate: Double,
        val open: Double,
        val high: Double,
        val low: Double,
        val volume: Long,
        val openInterest: Long,
        val openInterestChange: Long,
    )

    /** 선물 시장 투자자별 순매수(계약). 양수 = 순매수. 기관 세부는 기관계의 내역. */
    data class FuturesInvestors(
        val foreign: Long,
        val individual: Long,
        val institution: Long,
        val securities: Long, // 증권
        val insurance: Long, // 보험
        val merchantBank: Long, // 종금
        val trust: Long, // 투자신탁
        val privateEquity: Long, // 사모펀드
        val fund: Long, // 기금
        val bank: Long, // 은행
        val otherOrg: Long, // 기타단체
        val otherCorp: Long, // 기타법인
    )

    data class FuturesSummary(
        val name: String,
        val futuresPrice: Double,
        val changeRate: Double,
        val spot: Double,
        val basis: Double, // 시장 베이시스 = 선물 − 현물
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
