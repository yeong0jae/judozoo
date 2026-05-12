package at.backend.platform.kis.client

import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * VTS에서 호출하지 못하는 quotation API를 실거래 자격증명으로 호출하기 위한 클라이언트.
 * 활성 프로필이 vts/real 무엇이든 항상 실거래 도메인 + REAL_KIS_* 자격증명 사용.
 */
class KisRealQuotationClient(
    private val restClient: RestClient,
) {

    /** 존재하지 않는 코드거나 KIS가 에러 envelope(rt_cd != "0", output 없음)를 주면 null. */
    fun searchStock(keyword: String): KisStockSearchResponse.Output? {
        val response = restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/search-stock-info")
                    .queryParam("PRDT_TYPE_CD", PRDT_TYPE_DOMESTIC_STOCK)
                    .queryParam("PDNO", keyword)
                    .build()
            }
            .header("tr_id", "CTPF1002R")
            .retrieve()
            .body(KisStockSearchResponse::class.java)
            ?: error("KIS 종목 검색 응답이 비어있습니다")
        return response.output?.takeIf { response.rtCd == "0" }
    }

    fun checkHoliday(date: LocalDate): KisHolidayResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/chk-holiday")
                    .queryParam("BASS_DT", date.format(YYYYMMDD))
                    .queryParam("CTX_AREA_NK", "")
                    .queryParam("CTX_AREA_FK", "")
                    .build()
            }
            .header("tr_id", "CTCA0903R")
            .retrieve()
            .body(KisHolidayResponse::class.java)
            ?: error("KIS 휴장일 응답이 비어있습니다")

    companion object {
        private const val PRDT_TYPE_DOMESTIC_STOCK = "300"
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
