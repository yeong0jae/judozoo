package at.backend.platform.kiwoom.client

import at.backend.leadingstock.domain.IndexTick
import at.backend.platform.kiwoom.config.KiwoomApiProperties
import at.backend.stock.domain.Market
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

/**
 * 키움 업종 지수 조회. 종합(KOSPI), 코스닥 등 업종 코드 단위.
 * 코드: 001=종합(KOSPI), 101=종합(KOSDAQ).
 */
@Component
class KiwoomIndexClient(
    private val kiwoomRestClient: RestClient,
    @Suppress("unused") private val properties: KiwoomApiProperties,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 업종 현재가 요청 (ka20001). [mrktTp] 0=코스피, 1=코스닥. 실패 시 null 반환. */
    fun fetchIndex(indsCd: String, mrktTp: String = "0"): IndexSnapshot? {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching index from Kiwoom API — mrkt_tp={}, inds_cd={}", mrktTp, indsCd)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/sect")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka20001")
                .body(mapOf("mrkt_tp" to mrktTp, "inds_cd" to indsCd)) // mrkt_tp 0=코스피, 1=코스닥
                .retrieve()
                .body(IndexResponse::class.java)
                ?: return null

            if (response.return_code != null && response.return_code != 0) {
                log.error(
                    "Kiwoom index error. Code: {}, Message: {}",
                    response.return_code,
                    response.return_msg,
                )
                return null
            }

            return IndexSnapshot(
                indsCd = indsCd,
                // cur_prc 앞 부호는 등락 방향 표식 — 지수 레벨은 음수일 수 없으므로 크기(절댓값)만 사용
                currentValue = abs(parseSignedDouble(response.cur_prc)),
                changeRate = parseSignedDouble(response.flu_rt),
            )
        } catch (e: Exception) {
            log.error("Failed to fetch index inds_cd={}", indsCd, e)
            return null
        }
    }

    /** 시장별 종합 지수 코드/시장구분으로 당일 10초 틱만. (차트용) */
    fun fetchIndexTicks(market: Market, date: LocalDate = LocalDate.now()): List<IndexTick> =
        fetchIndexIntraday(market, date)?.ticks ?: emptyList()

    /** 시장별 종합 지수의 당일 인트라데이(지수값·등락률 + 10초 틱). 실패 시 null. */
    fun fetchIndexIntraday(market: Market, date: LocalDate = LocalDate.now()): IndexIntraday? = when (market) {
        Market.KOSPI -> fetchIndexIntraday(indsCd = "001", mrktTp = "0", date = date)
        Market.KOSDAQ -> fetchIndexIntraday(indsCd = "101", mrktTp = "1", date = date)
    }

    /**
     * 업종 지수 ka20001 — 상단 현재 지수값/등락률 + 장중 10초 틱(`inds_cur_prc_tm`, 시간 오름차순).
     * cur_prc 부호는 등락 방향 표식이라 지수값은 절댓값으로(ka20001은 소수 형식). 시각은 tm_n에 [date]를 붙임.
     */
    fun fetchIndexIntraday(indsCd: String, mrktTp: String, date: LocalDate = LocalDate.now()): IndexIntraday? {
        try {
            val token = authClient.getAccessToken()

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/sect")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka20001")
                .body(mapOf("mrkt_tp" to mrktTp, "inds_cd" to indsCd))
                .retrieve()
                .body(IndexResponse::class.java)
                ?: return null

            if (response.return_code != null && response.return_code != 0) {
                log.error("ka20001 error. Code: {}, Message: {}", response.return_code, response.return_msg)
                return null
            }

            val ticks = response.inds_cur_prc_tm.orEmpty()
                .mapNotNull { tick ->
                    val time = parseTime(tick.tm_n) ?: return@mapNotNull null
                    IndexTick(
                        at = LocalDateTime.of(date, time),
                        value = abs(parseSignedDouble(tick.cur_prc_n)),
                        volume = parseSignedDouble(tick.trde_qty_n).toLong(),
                    )
                }
                .sortedBy { it.at }

            return IndexIntraday(
                value = abs(parseSignedDouble(response.cur_prc)),
                changeRate = parseSignedDouble(response.flu_rt),
                ticks = ticks,
            )
        } catch (e: Exception) {
            log.error("Failed to fetch index intraday inds_cd={}", indsCd, e)
            return null
        }
    }

    /** "143000"(HHmmss) → LocalTime. 형식이 어긋나면 null. */
    private fun parseTime(value: String?): LocalTime? {
        val s = value?.trim() ?: return null
        if (s.length != 6) return null
        return runCatching {
            LocalTime.of(s.substring(0, 2).toInt(), s.substring(2, 4).toInt(), s.substring(4, 6).toInt())
        }.getOrNull()
    }

    /** "+1.23", "1.23-", "-1.23" 등 키움 부호 변종을 모두 흡수해 Double로. */
    private fun parseSignedDouble(value: String?): Double {
        val s = value?.trim() ?: return 0.0
        if (s.isEmpty()) return 0.0
        val signed = when {
            s.startsWith("+") -> s.substring(1)
            s.endsWith("-") -> "-" + s.dropLast(1)
            else -> s
        }
        return signed.toDoubleOrNull() ?: 0.0
    }

    data class IndexResponse(
        val cur_prc: String? = null,    // 현재가(지수값)
        val flu_rt: String? = null,     // 등락률
        val pred_pre: String? = null,   // 전일대비
        val pred_pre_sig: String? = null,
        val inds_nm: String? = null,    // 업종명
        val inds_cur_prc_tm: List<IndexTimeItem>? = null, // 장중 10초 시세
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class IndexTimeItem(
        val tm_n: String? = null,       // 시각 HHmmss
        val cur_prc_n: String? = null,  // 그 시각 지수값(부호 포함)
        val trde_qty_n: String? = null, // 그 시각 거래량(1000주)
    )

    /** 지수 인트라데이 — 현재 지수값/등락률 + 당일 10초 틱. */
    data class IndexIntraday(
        val value: Double,
        val changeRate: Double,
        val ticks: List<IndexTick>,
    )

    /** 지수 한 시점 스냅샷. 도메인 객체로 격리해 클라이언트 응답 구조 변경에 대응. */
    data class IndexSnapshot(
        val indsCd: String,
        val currentValue: Double,
        val changeRate: Double,
    )
}
