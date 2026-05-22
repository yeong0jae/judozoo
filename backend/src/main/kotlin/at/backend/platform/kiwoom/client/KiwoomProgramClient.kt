package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

@Component
class KiwoomProgramClient(
    private val kiwoomRestClient: RestClient,
    private val properties: KiwoomApiProperties,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 종목 일별 프로그램 매매 추이 (ka90013) */
    fun fetchProgramTrading(stockCode: String): ProgramTradingData? {
        try {
            val token = authClient.getAccessToken()
            val today = LocalDate.now().toString().replace("-", "")

            log.info("Fetching program trading for {} from Kiwoom API", stockCode)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/mrkcond")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka90013")
                .body(
                    mapOf(
                        "amt_qty_tp" to "1", // 1:금액
                        "stk_cd" to stockCode,
                        "date" to today,
                    ),
                )
                .retrieve()
                .body(ProgramTradingResponse::class.java)
                ?: return null

            val item = response.stk_daly_prm_trde_trnsn.firstOrNull() ?: return null

            return ProgramTradingData(
                stockCode = stockCode,
                date = item.dt,
                programSellAmount = parseAmount(item.prm_sell_amt),
                programBuyAmount = parseAmount(item.prm_buy_amt),
                programNetBuyAmount = parseAmount(item.prm_netprps_amt),
            )
        } catch (e: Exception) {
            log.error("Failed to fetch program trading for {}", stockCode, e)
            return null
        }
    }

    fun fetchProgramNetBuy(stockCode: String): Long =
        fetchProgramTrading(stockCode)?.programNetBuyAmount ?: 0L

    /** 키움이 음수를 "-12345", "12345-", "--12345" 등 다양한 형태로 반환하는 것 처리 */
    private fun parseAmount(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return 0L

        return try {
            when {
                trimmed.startsWith("--") -> -(trimmed.substring(2).toLongOrNull() ?: 0)
                trimmed.startsWith("-") -> trimmed.toLongOrNull() ?: 0L
                trimmed.endsWith("-") -> -(trimmed.dropLast(1).toLongOrNull() ?: 0)
                else -> trimmed.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            log.warn("Failed to parse amount: {}", value, e)
            0L
        }
    }

    // --- Response DTOs ---

    data class ProgramTradingResponse(
        val stk_daly_prm_trde_trnsn: List<ProgramTradingItem>,
    )

    data class ProgramTradingItem(
        val dt: String,
        val cur_prc: String,
        val pre_sig: String,
        val pred_pre: String,
        val flu_rt: String,
        val trde_qty: String,
        val prm_sell_amt: String,
        val prm_buy_amt: String,
        val prm_netprps_amt: String,
        val prm_netprps_amt_irds: String,
        val prm_sell_qty: String,
        val prm_buy_qty: String,
        val prm_netprps_qty: String,
        val prm_netprps_qty_irds: String,
        val base_pric_tm: String,
        val dbrt_trde_rpy_sum: String,
        val remn_rcvord_sum: String,
        val stex_tp: String,
    )

    data class ProgramTradingData(
        val stockCode: String,
        val date: String,
        val programSellAmount: Long,
        val programBuyAmount: Long,
        val programNetBuyAmount: Long,
    )
}
