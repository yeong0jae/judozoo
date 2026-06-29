package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class KisOverseasChartClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 해외주식 1분봉 (HHDFS76950200) — 최근 120건. 가격은 이미 소수점 적용, 시각은 한국기준(kymd+khms). */
    @Cacheable("kisOverseasMinuteCandles", key = "#excd + ':' + #symb")
    fun fetchMinuteCandles(excd: String, symb: String): List<OverseasMinuteCandle> {
        val token = authClient.getAccessToken()
        val response = kisRestClient.get()
            .uri { b ->
                b.path("/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", excd)
                    .queryParam("SYMB", symb)
                    .queryParam("NMIN", "1")
                    .queryParam("PINC", "1") // 전일포함 — 장 초반에도 직전 세션 봉으로 채움
                    .queryParam("NEXT", "")
                    .queryParam("NREC", "120")
                    .queryParam("FILL", "")
                    .queryParam("KEYB", "")
                    .build()
            }
            .headers { it.applyKisHeaders(token, "HHDFS76950200") }
            .retrieve()
            .body(MinuteResponse::class.java)
            ?: throw IllegalStateException("KIS 해외 분봉 응답이 null ($excd:$symb)")

        if (response.rt_cd != "0") {
            throw IllegalStateException("KIS 해외 분봉 오류: ${response.msg1} ($excd:$symb)")
        }
        return (response.output2 ?: emptyList()).mapNotNull { it.toCandle() }
    }

    /** 해외주식 일봉 (HHDFS76240000, GUBN=0) — 최근 100건. 가격 소수점 적용, 최신→과거 순. */
    @Cacheable("kisOverseasDailyCandles", key = "#excd + ':' + #symb")
    fun fetchDailyCandles(excd: String, symb: String): List<OverseasDailyCandle> {
        val token = authClient.getAccessToken()
        val response = kisRestClient.get()
            .uri { b ->
                b.path("/uapi/overseas-price/v1/quotations/dailyprice")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", excd)
                    .queryParam("SYMB", symb)
                    .queryParam("GUBN", "0") // 0:일
                    .queryParam("BYMD", "")  // 공란 = 오늘 기준
                    .queryParam("MODP", "1") // 수정주가 반영
                    .queryParam("KEYB", "")
                    .build()
            }
            .headers { it.applyKisHeaders(token, "HHDFS76240000") }
            .retrieve()
            .body(DailyResponse::class.java)
            ?: throw IllegalStateException("KIS 해외 일봉 응답이 null ($excd:$symb)")

        if (response.rt_cd != "0") {
            throw IllegalStateException("KIS 해외 일봉 오류: ${response.msg1} ($excd:$symb)")
        }
        return (response.output2 ?: emptyList()).mapNotNull { it.toCandle() }
    }

    private fun org.springframework.http.HttpHeaders.applyKisHeaders(token: String, trId: String) {
        set("content-type", "application/json; charset=utf-8")
        set("authorization", "Bearer $token")
        set("appkey", properties.appKey)
        set("appsecret", properties.appSecret)
        set("tr_id", trId)
        set("custtype", "P")
    }

    private fun MinuteItem.toCandle(): OverseasMinuteCandle? {
        val dateTime = runCatching {
            LocalDateTime.parse(kymd.trim() + khms.trim().padStart(6, '0'), MINUTE_FMT)
        }.getOrNull() ?: return null
        return OverseasMinuteCandle(
            dateTime = dateTime,
            open = open.toDoubleOrNull() ?: 0.0,
            high = high.toDoubleOrNull() ?: 0.0,
            low = low.toDoubleOrNull() ?: 0.0,
            close = last.toDoubleOrNull() ?: 0.0,
            volume = evol.toLongOrNull() ?: 0,
            tradingValue = eamt.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun DailyItem.toCandle(): OverseasDailyCandle? {
        val date = runCatching { LocalDate.parse(xymd.trim(), DAILY_FMT) }.getOrNull() ?: return null
        return OverseasDailyCandle(
            date = date,
            open = open.toDoubleOrNull() ?: 0.0,
            high = high.toDoubleOrNull() ?: 0.0,
            low = low.toDoubleOrNull() ?: 0.0,
            close = clos.toDoubleOrNull() ?: 0.0,
            volume = tvol.toLongOrNull() ?: 0,
        )
    }

    data class OverseasMinuteCandle(
        val dateTime: LocalDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val tradingValue: Double,
    )

    data class OverseasDailyCandle(
        val date: LocalDate,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
    )

    private data class MinuteResponse(
        val rt_cd: String,
        val msg1: String,
        val output2: List<MinuteItem>?,
    )

    private data class MinuteItem(
        val kymd: String, // 한국기준일자 YYYYMMDD
        val khms: String, // 한국기준시간 HHMMSS
        val open: String,
        val high: String,
        val low: String,
        val last: String, // 종가
        val evol: String, // 체결량
        val eamt: String, // 체결대금
    )

    private data class DailyResponse(
        val rt_cd: String,
        val msg1: String,
        val output2: List<DailyItem>?,
    )

    private data class DailyItem(
        val xymd: String, // 일자 YYYYMMDD
        val clos: String, // 종가
        val open: String,
        val high: String,
        val low: String,
        val tvol: String, // 거래량
    )

    companion object {
        private val MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val DAILY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
