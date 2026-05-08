package at.backend.platform.kis.client

import at.backend.platform.kis.client.request.KisOrderCancelRequest
import at.backend.platform.kis.client.request.KisOrderRequest
import at.backend.platform.kis.client.response.*
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class KisRestClient(
    private val accountNo: String,
    private val accountProductCode: String,
    private val trIdBuy: String,
    private val trIdSell: String,
    private val trIdCancel: String,
    private val trIdBalance: String,
    private val marketDivCode: String,
    private val exchangeId: String,
    private val restClient: RestClient,
) {

    fun getCurrentPrice(stockCode: String): KisCurrentPriceResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                    .queryParam("FID_COND_MRKT_DIV_CODE", marketDivCode)
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
                    .queryParam("PRDT_TYPE_CD", PRDT_TYPE_DOMESTIC_STOCK)
                    .queryParam("PDNO", keyword)
                    .build()
            }
            .header("tr_id", "CTPF1002R")
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
                    .queryParam("INQR_DVSN", "01")
                    .queryParam("UNPR_DVSN", "01")
                    .queryParam("FUND_STTL_ICLD_YN", "N")
                    .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                    .queryParam("PRCS_DVSN", "00")
                    .queryParam("CTX_AREA_FK100", "")
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .header("tr_id", trIdBalance)
            .retrieve()
            .body(KisBalanceResponse::class.java)
            ?: error("KIS 잔고 응답이 비어있습니다")

    fun getBars(stockCode: String): KisBarResponse =
        restClient.get()
            .uri {
                it.path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                    .queryParam("FID_ETC_CLS_CODE", "")
                    .queryParam("FID_COND_MRKT_DIV_CODE", marketDivCode)
                    .queryParam("FID_INPUT_ISCD", stockCode)
                    .queryParam("FID_INPUT_HOUR_1", MARKET_CLOSE_HHMMSS)
                    .queryParam("FID_PW_DATA_INCU_YN", "N")
                    .build()
            }
            .header("tr_id", "FHKST03010200")
            .retrieve()
            .body(KisBarResponse::class.java)
            ?: error("KIS 분봉 응답이 비어있습니다")

    fun requestOrder(stockCode: String, side: String, qty: Int): KisOrderResponse {
        val trId = when (side) {
            "BUY" -> trIdBuy
            "SELL" -> trIdSell
            else -> error("알 수 없는 주문 side: $side")
        }
        val response = restClient.post()
            .uri("/uapi/domestic-stock/v1/trading/order-cash")
            .header("tr_id", trId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                KisOrderRequest(
                    cano = accountNo,
                    acntPrdtCd = accountProductCode,
                    pdno = stockCode,
                    ordDvsn = ORD_DVSN_MARKET,
                    ordQty = qty.toString(),
                    ordUnpr = ORD_UNPR_MARKET,
                    excgIdDvsnCd = exchangeId,
                )
            )
            .retrieve()
            .body(KisOrderResponse::class.java)
            ?: error("KIS 주문 응답이 비어있습니다")
        return response.requireSuccess("KIS 주문")
    }

    fun cancelRemainder(krxFwdgOrdOrgno: String, originalOdno: String): KisOrderResponse {
        val response = restClient.post()
            .uri("/uapi/domestic-stock/v1/trading/order-rvsecncl")
            .header("tr_id", trIdCancel)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                KisOrderCancelRequest(
                    cano = accountNo,
                    acntPrdtCd = accountProductCode,
                    krxFwdgOrdOrgno = krxFwdgOrdOrgno,
                    orgnOdno = originalOdno,
                    ordDvsn = ORD_DVSN_MARKET,
                    rvseCnclDvsnCd = RVSE_CNCL_CANCEL,
                    ordQty = "0",
                    ordUnpr = ORD_UNPR_MARKET,
                    qtyAllOrdYn = "Y",
                )
            )
            .retrieve()
            .body(KisOrderResponse::class.java)
            ?: error("KIS 주문 취소 응답이 비어있습니다")
        return response.requireSuccess("KIS 주문 취소")
    }

    private fun KisOrderResponse.requireSuccess(label: String): KisOrderResponse {
        if (rtCd != RT_CD_OK) {
            throw KisOrderRejectedException(msgCd, "$label 거부 ${msg1 ?: ""}")
        }
        return this
    }

    companion object {
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private const val MARKET_CLOSE_HHMMSS = "153000"

        // PRDT_TYPE_CD: 상품 유형 (300=국내주식, 301=해외주식, 302=선물옵션, 701=ETF, ...)
        private const val PRDT_TYPE_DOMESTIC_STOCK = "300"

        private const val ORD_DVSN_MARKET = "01"
        private const val ORD_UNPR_MARKET = "0"
        private const val RVSE_CNCL_CANCEL = "02"
        private const val RT_CD_OK = "0"
    }
}
