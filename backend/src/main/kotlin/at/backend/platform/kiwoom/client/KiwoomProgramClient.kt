package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import at.backend.stock.domain.Market
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

            val item = response.stk_daly_prm_trde_trnsn?.firstOrNull() ?: return null

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

    /**
     * 시장 일자별 프로그램 매매 추이 (ka90010) — 최근 N일, 최신→과거. 금액 단위 백만원.
     * 첫 행(오늘)은 그 시점까지의 당일 누적이라, 폴러가 이 값을 스냅샷으로 찍어 세션을 만든다.
     */
    fun fetchMarketProgramDaily(market: Market, date: LocalDate): List<MarketProgramPoint> {
        try {
            val token = authClient.getAccessToken()
            val response = kiwoomRestClient.post()
                .uri("/api/dostk/mrkcond")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka90010")
                .body(
                    mapOf(
                        "date" to date.toString().replace("-", ""),
                        "amt_qty_tp" to "1",  // 1:금액(백만원)
                        "mrkt_tp" to market.programMrktCode(),
                        "min_tic_tp" to "0",
                        "stex_tp" to "3",     // 3:통합(KRX+NXT)
                    ),
                )
                .retrieve()
                .body(MarketProgramResponse::class.java)
                ?: return emptyList()
            if (response.return_code != null && response.return_code != 0) {
                log.error("ka90010 error. Code: {}, Msg: {}", response.return_code, response.return_msg)
                return emptyList()
            }
            return response.prm_trde_trnsn.orEmpty().map {
                MarketProgramPoint(
                    date = runCatching { LocalDate.parse(it.cntr_tm.trim().take(8), BASIC_DATE) }.getOrNull(),
                    arbitrageNet = parseAmount(it.dfrt_trde_netprps),
                    nonArbitrageNet = parseAmount(it.ndiffpro_trde_netprps),
                    totalNet = parseAmount(it.all_netprps),
                )
            }
        } catch (e: Exception) {
            log.error("Failed to fetch market program daily ({})", market, e)
            return emptyList()
        }
    }

    /** 프로그램 매매 시장구분 코드 — 통합(KRX+NXT) 기준. 코스피 P001_AL01, 코스닥 P101_AL02. */
    private fun Market.programMrktCode() = when (this) {
        Market.KOSPI -> "P001_AL01"
        Market.KOSDAQ -> "P101_AL02"
    }

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
        // 응답에서 missing 가능 (실 운영 로그에서 KotlinInvalidNullException 확인됨) — nullable + 사용처에서 null-safe 처리.
        val stk_daly_prm_trde_trnsn: List<ProgramTradingItem>? = null,
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

    // --- 시장 프로그램 매매 추이 (ka90005/ka90010) ---

    data class MarketProgramResponse(
        val prm_trde_trnsn: List<MarketProgramItem>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class MarketProgramItem(
        val cntr_tm: String = "",            // 일자별 yyyyMMddHHmmss
        val dfrt_trde_netprps: String = "",  // 차익거래 순매수(백만원, 부호)
        val ndiffpro_trde_netprps: String = "", // 비차익거래 순매수(백만원, 부호)
        val all_netprps: String = "",        // 전체 순매수(백만원, 부호)
    )

    /** 하루치 프로그램 순매수(백만원). 최신→과거로 들어온다. */
    data class MarketProgramPoint(
        val date: LocalDate?,
        val arbitrageNet: Long,     // 차익
        val nonArbitrageNet: Long,  // 비차익
        val totalNet: Long,         // 전체
    )

    companion object {
        private val BASIC_DATE = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
