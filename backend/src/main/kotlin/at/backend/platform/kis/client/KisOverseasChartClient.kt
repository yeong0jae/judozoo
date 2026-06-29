package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class KisOverseasChartClient(
    private val kisRestClient: RestClient,
    private val authClient: KisAuthClient,
    private val properties: KisApiProperties,
) {

    /** 해외주식 일봉 (HHDFS76240000, GUBN=0) — 최근 [DAILY_COUNT]건. 가격 소수점 적용, 최신→과거 순. */
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
        return (response.output2 ?: emptyList()).take(DAILY_COUNT).mapNotNull { it.toCandle() }
    }

    private fun org.springframework.http.HttpHeaders.applyKisHeaders(token: String, trId: String) {
        set("content-type", "application/json; charset=utf-8")
        set("authorization", "Bearer $token")
        set("appkey", properties.appKey)
        set("appsecret", properties.appSecret)
        set("tr_id", trId)
        set("custtype", "P")
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

    data class OverseasDailyCandle(
        val date: LocalDate,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
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
        private const val DAILY_COUNT = 60 // 일봉 표시 거래일 수 (국내와 동일)
        private val DAILY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
