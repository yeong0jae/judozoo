package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 해외지수 기간별시세 (FHKST03030100, /uapi/overseas-price/v1/quotations/inquire-daily-chartprice).
 * FID_COND_MRKT_DIV_CODE=N(해외지수)로 나스닥종합(COMP) 등 지수의 종가·등락률을 읽는다.
 * 마감 후엔 종가시세가 내려오므로 장 마감 스냅샷에 쓴다. output1(기본정보)이 최신 종가/전일대비율을 담는다.
 */
@Component
class KisOverseasIndexClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * [iscd] 해외지수 마스터 코드(예: 나스닥종합 "COMP"). [from]~[to]는 일별 조회 구간(미국 영업일 기준).
     * 종가·등락률은 output1에서 읽는다. 실패·응답오류·파싱불가 시 null(캡처가 스킵하게).
     */
    fun fetchIndexDailyClose(iscd: String, from: LocalDate, to: LocalDate): OverseasIndexQuote? {
        try {
            val token = authClient.getAccessToken()

            val response = kisRestClient.get()
                .uri { builder ->
                    builder.path("/uapi/overseas-price/v1/quotations/inquire-daily-chartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "N")
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
                .header("tr_id", "FHKST03030100")
                .header("custtype", "P")
                .retrieve()
                .body(IndexChartResponse::class.java)
                ?: return null

            if (response.rt_cd != "0") {
                log.error("KIS 해외지수 오류: code={}, msg={} (iscd={})", response.msg_cd, response.msg1, iscd)
                return null
            }

            val o = response.output1 ?: return null
            val price = o.ovrs_nmix_prpr?.trim()?.toDoubleOrNull() ?: return null
            // prdy_ctrt(전일대비율)는 절댓값일 수 있어 부호는 prdy_vrss_sign으로 — 4:하한 5:하락이면 음수.
            val magnitude = o.prdy_ctrt?.trim()?.toDoubleOrNull() ?: 0.0
            val changeRate = if (o.prdy_vrss_sign?.trim() in setOf("4", "5")) -magnitude else magnitude
            // 실제 영업일은 일자별(output2)의 최신 stck_bsop_date — 미국 휴장일에도 종가가 찍힌 진짜 날짜를 쓴다.
            val tradeDate = response.output2.orEmpty()
                .mapNotNull { runCatching { LocalDate.parse(it.stck_bsop_date?.trim(), dateFmt) }.getOrNull() }
                .maxOrNull() ?: to

            return OverseasIndexQuote(
                code = iscd,
                name = o.hts_kor_isnm?.trim() ?: iscd,
                price = price,
                changeRate = changeRate,
                tradeDate = tradeDate,
            )
        } catch (e: Exception) {
            log.error("KIS 해외지수 조회 실패 (iscd={})", iscd, e)
            return null
        }
    }

    data class IndexChartResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output1: IndexBasicInfo? = null,
        val output2: List<IndexDailyItem>? = null,
    )

    data class IndexBasicInfo(
        val ovrs_nmix_prpr: String? = null,    // 현재가(마감 후 종가)
        val prdy_ctrt: String? = null,         // 전일 대비율(절댓값일 수 있음)
        val prdy_vrss_sign: String? = null,    // 전일 대비 부호 (4·5=하락)
        val ovrs_nmix_prdy_vrss: String? = null, // 전일 대비
        val hts_kor_isnm: String? = null,      // HTS 한글 종목명
    )

    data class IndexDailyItem(
        val stck_bsop_date: String? = null,    // 영업 일자(YYYYMMDD)
    )

    /** 해외지수 한 종목의 종가·등락률(부호 포함, %) + 실제 영업일. */
    data class OverseasIndexQuote(
        val code: String,
        val name: String,
        val price: Double,
        val changeRate: Double,
        val tradeDate: LocalDate,
    )
}
