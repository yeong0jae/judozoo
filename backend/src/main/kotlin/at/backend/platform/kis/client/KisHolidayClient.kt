package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 국내 휴장일 조회 (CTCA0903R) — [baseDt]부터 미래로 개장일 여부를 준다.
 * 원장 연동 API라 "1일 1회 호출 권장" — 호출측([HolidayService])이 하루 캐시로 감싼다.
 * 실패 시 빈 집합(호출측이 주말만으로 폴백하게).
 */
@Component
class KisHolidayClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** [baseDt] 이후 개장일(주식시장 여는 날, opnd_yn=Y) 집합. 한 응답에 약 24일치가 온다. */
    fun fetchOpenDays(baseDt: LocalDate): Set<LocalDate> {
        try {
            val token = authClient.getAccessToken()
            val response = kisRestClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/quotations/chk-holiday")
                        .queryParam("BASS_DT", baseDt.format(dateFmt))
                        .queryParam("CTX_AREA_NK", "")
                        .queryParam("CTX_AREA_FK", "")
                        .build()
                }
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer $token")
                .header("appkey", properties.appKey)
                .header("appsecret", properties.appSecret)
                .header("tr_id", "CTCA0903R")
                .header("custtype", "P")
                .retrieve()
                .body(HolidayResponse::class.java)
                ?: return emptySet()
            if (response.rt_cd != "0") {
                log.error("KIS 휴장일 오류: code={}, msg={}", response.msg_cd, response.msg1)
                return emptySet()
            }
            return response.output.orEmpty()
                .filter { it.opnd_yn?.trim() == "Y" }
                .mapNotNull { runCatching { LocalDate.parse(it.bass_dt?.trim(), dateFmt) }.getOrNull() }
                .toSet()
        } catch (e: Exception) {
            log.error("KIS 휴장일 조회 실패 (baseDt={})", baseDt, e)
            return emptySet()
        }
    }

    data class HolidayResponse(
        val rt_cd: String? = null,
        val msg_cd: String? = null,
        val msg1: String? = null,
        val output: List<HolidayItem>? = null,
    )

    data class HolidayItem(
        val bass_dt: String? = null,
        val opnd_yn: String? = null, // 개장일 여부 Y/N
    )
}
