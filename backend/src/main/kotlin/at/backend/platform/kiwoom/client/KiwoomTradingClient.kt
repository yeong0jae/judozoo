package at.backend.platform.kiwoom.client

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Kiwoom 트레이딩 REST. `/api/dostk/...` 경로에 api-id 헤더로 분기.
 * 응답은 항상 HTTP 200 + JSON body로 오며, `return_code != 0`이면 거부 → [KiwoomOrderRejectedException].
 *
 * 사용 api-id:
 *   - ka10001 (stkinfo) — 현재가·종목명 (재사용: leadingstock의 KiwoomMarketClient도 사용)
 *   - kt00004 (acnt)    — 계좌 평가/예수금
 *   - kt10000 (ordr)    — 매수 주문
 *   - kt10001 (ordr)    — 매도 주문
 *   - kt10003 (ordr)    — 주문 취소
 */
class KiwoomTradingClient(
    private val restClient: RestClient,
    private val authClient: KiwoomAuthClient,
    private val dmstStexTp: String,
) {
    private val log = KotlinLogging.logger {}

    /** 현재가·종목명 동시 조회. 종목 미존재 시 null. */
    fun fetchStockInfo(stockCode: String): StockInfoOutput? {
        // _AL(SOR 통합) 우선 — NXT 애프터마켓 시간대에도 통합 현재가 반환. 빈 응답이면 KRX 기본으로 폴백.
        val alResp = requestStockInfo("${stockCode}_AL")
        val resp = if (alResp.return_code == 0 && !alResp.stk_nm.isNullOrBlank() && !alResp.cur_prc.isNullOrBlank()) {
            alResp
        } else {
            log.warn {
                "Kiwoom ka10001 _AL 빈 응답 → KRX 폴백: stk_cd=${stockCode}_AL " +
                    "code=${alResp.return_code} msg=${alResp.return_msg} nm=${alResp.stk_nm} prc=${alResp.cur_prc}"
            }
            requestStockInfo(stockCode)
        }
        if (resp.return_code != 0) {
            log.warn { "Kiwoom ka10001 거부: code=${resp.return_code}, msg=${resp.return_msg}" }
            return null
        }
        val name = resp.stk_nm?.takeIf { it.isNotBlank() } ?: return null
        return StockInfoOutput(stockName = name, currentPrice = parsePrice(resp.cur_prc))
    }

    private fun requestStockInfo(stkCd: String): StockInfoResponse =
        post(
            endpoint = "/api/dostk/stkinfo",
            apiId = "ka10001",
            body = mapOf("stk_cd" to stkCd),
            type = StockInfoResponse::class.java,
        )

    /** D+2 예수금. */
    fun fetchAvailableCash(): Long = fetchAccountEvaluation().d2_entra?.toLongOrNull() ?: 0L

    /** 보유 종목 평가 — kt00004의 stk_acnt_evlt_prst. */
    fun fetchHoldingsRaw(): List<AccountEvaluationResponse.StockHolding> =
        fetchAccountEvaluation().stk_acnt_evlt_prst ?: emptyList()

    private fun fetchAccountEvaluation(): AccountEvaluationResponse {
        val resp = post(
            endpoint = "/api/dostk/acnt",
            apiId = "kt00004",
            body = mapOf(
                "qry_tp" to "0",
                "dmst_stex_tp" to "KRX",
            ),
            type = AccountEvaluationResponse::class.java,
        )
        if (resp.return_code != 0) {
            throw KiwoomOrderRejectedException(resp.return_code.toString(), "Kiwoom 잔고 조회 거부 ${resp.return_msg ?: ""}")
        }
        return resp
    }

    fun placeBuyOrder(stockCode: String, qty: Int): String = placeOrder(apiId = "kt10000", stockCode = stockCode, qty = qty, label = "Kiwoom 매수")

    fun placeSellOrder(stockCode: String, qty: Int): String = placeOrder(apiId = "kt10001", stockCode = stockCode, qty = qty, label = "Kiwoom 매도")

    private fun placeOrder(apiId: String, stockCode: String, qty: Int, label: String): String {
        val resp = post(
            endpoint = "/api/dostk/ordr",
            apiId = apiId,
            body = mapOf(
                "dmst_stex_tp" to dmstStexTp,
                "stk_cd" to stockCode,
                "ord_qty" to qty.toString(),
                "ord_uv" to "",
                "trde_tp" to "3",          // 시장가
                "cond_uv" to "",
            ),
            type = OrderResponse::class.java,
        )
        if (resp.return_code != 0) {
            throw KiwoomOrderRejectedException(resp.return_code.toString(), "$label 거부 ${resp.return_msg ?: ""}")
        }
        return resp.ord_no ?: throw KiwoomOrderRejectedException("EMPTY_ORD_NO", "$label 응답에 주문번호 없음")
    }

    /** 분봉 차트 조회. tic_scope="3"으로 3분봉 받음. 최신 → 과거 순. */
    fun fetchMinuteBars(stockCode: String): List<MinuteBarItem> {
        val resp = post(
            endpoint = "/api/dostk/chart",
            apiId = "ka10080",
            body = mapOf(
                "stk_cd" to stockCode,
                "tic_scope" to "3",
                "upd_stkpc_tp" to "1",
                "base_dt" to LocalDate.now().format(YYYYMMDD),
            ),
            type = MinuteBarsResponse::class.java,
        )
        if (resp.return_code != 0) {
            log.warn { "Kiwoom ka10080 거부: code=${resp.return_code}, msg=${resp.return_msg}" }
            return emptyList()
        }
        return resp.stk_min_pole_chart_qry ?: emptyList()
    }

    fun cancelOrder(stockCode: String, originalOrderNo: String) {
        val resp = post(
            endpoint = "/api/dostk/ordr",
            apiId = "kt10003",
            body = mapOf(
                "dmst_stex_tp" to dmstStexTp,
                "orig_ord_no" to originalOrderNo,
                "stk_cd" to stockCode,
                "cncl_qty" to "0",          // 잔량 전부 취소
            ),
            type = OrderResponse::class.java,
        )
        if (resp.return_code != 0) {
            throw KiwoomOrderRejectedException(resp.return_code.toString(), "Kiwoom 취소 거부 ${resp.return_msg ?: ""}")
        }
    }

    private fun <T : Any> post(endpoint: String, apiId: String, body: Map<String, Any>, type: Class<T>): T {
        val token = authClient.getAccessToken()
        log.info { "Kiwoom 호출 → POST $endpoint api-id=$apiId" }
        val response = restClient.post()
            .uri(endpoint)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("authorization", "Bearer $token")
            .header("api-id", apiId)
            .body(body)
            .retrieve()
            .body(type)
            ?: error("Kiwoom 응답 빈 본문: api-id=$apiId")
        log.info { "Kiwoom 응답 ← POST $endpoint api-id=$apiId" }
        return response
    }

    /** Kiwoom 가격: +/- 부호(전일대비 방향) 제거 후 절대값 Long. */
    private fun parsePrice(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val cleaned = value.trim().removePrefix("+").removePrefix("-")
        return cleaned.toLongOrNull() ?: 0L
    }

    data class StockInfoOutput(val stockName: String, val currentPrice: Long)

    data class StockInfoResponse(
        val stk_cd: String? = null,
        val stk_nm: String? = null,
        val cur_prc: String? = null,
        val return_code: Int = 0,
        val return_msg: String? = null,
    )

    data class AccountEvaluationResponse(
        val d2_entra: String? = null,
        val stk_acnt_evlt_prst: List<StockHolding>? = null,
        val return_code: Int = 0,
        val return_msg: String? = null,
    ) {
        /** Kiwoom 보유 종목 응답 한 행. stk_cd는 "A005930" 같이 prefix가 붙어 올 수 있음. */
        data class StockHolding(
            val stk_cd: String,
            val stk_nm: String,
            val rmnd_qty: String,
            val avg_prc: String,
            val cur_prc: String,
        )
    }

    data class OrderResponse(
        val ord_no: String? = null,
        val return_code: Int = 0,
        val return_msg: String? = null,
    )

    data class MinuteBarsResponse(
        val stk_min_pole_chart_qry: List<MinuteBarItem>? = null,
        val return_code: Int = 0,
        val return_msg: String? = null,
    )

    /** ka10080 응답 분봉 1개. 가격 필드는 모두 +/- 부호 접두 가능 (전일대비 방향 표시). */
    data class MinuteBarItem(
        val cntr_tm: String,       // YYYYMMDDHHmmss
        val open_pric: String,
        val high_pric: String,
        val low_pric: String,
        val cur_prc: String,       // 종가 (해당 봉 마감 가격)
        val trde_qty: String,
    )

    companion object {
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
