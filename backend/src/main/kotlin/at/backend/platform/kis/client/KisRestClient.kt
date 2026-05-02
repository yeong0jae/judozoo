package at.backend.platform.kis.client

import at.backend.platform.kis.client.response.KisBalanceResponse
import at.backend.platform.kis.client.response.KisBarResponse
import at.backend.platform.kis.client.response.KisCurrentPriceResponse
import at.backend.platform.kis.client.response.KisDailyCcldResponse
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.platform.kis.client.response.KisStockSearchResponse
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class KisRestClient(
    private val accountNo: String,
    private val accountProductCode: String,
    private val restClient: RestClient,
) {

    fun getCurrentPrice(stockCode: String): KisCurrentPriceResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", stockCode)
                    .build()
            }
            .header("tr_id", "FHKST01010100")
            .retrieve()
            .body(KisCurrentPriceResponse::class.java)
            ?: error("KIS 현재가 응답이 비어있습니다")

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

    fun searchStock(keyword: String): KisStockSearchResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/search-stock-info")
                    .queryParam("PRDT_TYPE_CD", "300")
                    .queryParam("PDNO", keyword)
                    .build()
            }
            .header("tr_id", "CTPF1604R")
            .retrieve()
            .body(KisStockSearchResponse::class.java)
            ?: error("KIS 종목 검색 응답이 비어있습니다")

    fun getBalance(): KisBalanceResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/trading/inquire-balance")
                    .queryParam("CANO", accountNo)
                    .queryParam("ACNT_PRDT_CD", accountProductCode)
                    .queryParam("AFHR_FLPR_YN", "N")
                    .queryParam("OFL_YN", "")
                    .queryParam("INQR_DVSN", "02")
                    .queryParam("UNPR_DVSN", "01")
                    .queryParam("FUND_STTL_ICLD_YN", "N")
                    .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                    .queryParam("PRCS_DVSN", "00")
                    .queryParam("CTX_AREA_FK100", "")
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .header("tr_id", "TTTC8434R")
            .retrieve()
            .body(KisBalanceResponse::class.java)
            ?: error("KIS 잔고 응답이 비어있습니다")

    fun getBars(stockCode: String): KisBarResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                    .queryParam("FID_ETC_CLS_CODE", "")
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", stockCode)
                    .queryParam("FID_INPUT_HOUR_1", MARKET_CLOSE_HHMMSS)
                    .queryParam("FID_PW_DATA_INCU_YN", "N")
                    .build()
            }
            .header("tr_id", "FHKST03010200")
            .retrieve()
            .body(KisBarResponse::class.java)
            ?: error("KIS 분봉 응답이 비어있습니다")

    fun getDailyExecutions(stockCode: String, date: LocalDate): KisDailyCcldResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
                    .queryParam("CANO", accountNo)
                    .queryParam("ACNT_PRDT_CD", accountProductCode)
                    .queryParam("INQR_STRT_DT", date.format(YYYYMMDD))
                    .queryParam("INQR_END_DT", date.format(YYYYMMDD))
                    .queryParam("SLL_BUY_DVSN_CD", "00")
                    .queryParam("INQR_DVSN", "00")
                    .queryParam("PDNO", stockCode)
                    .queryParam("CCLD_DVSN", "01")
                    .queryParam("ORD_GNO_BRNO", "")
                    .queryParam("ODNO", "")
                    .queryParam("INQR_DVSN_3", "00")
                    .queryParam("INQR_DVSN_1", "")
                    .queryParam("CTX_AREA_FK100", "")
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .header("tr_id", "TTTC8001R")
            .retrieve()
            .body(KisDailyCcldResponse::class.java)
            ?: error("KIS 일별 체결 응답이 비어있습니다")

    companion object {
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private const val MARKET_CLOSE_HHMMSS = "153000"
    }
}
