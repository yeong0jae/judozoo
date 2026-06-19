package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
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

    /** 업종 현재가 요청 (ka20001). 실패 시 null 반환. */
    fun fetchIndex(indsCd: String): IndexSnapshot? {
        try {
            val token = authClient.getAccessToken()
            log.info("Fetching index from Kiwoom API — inds_cd={}", indsCd)

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/sect")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka20001")
                .body(mapOf("mrkt_tp" to "0", "inds_cd" to indsCd)) // mrkt_tp 0=코스피, 1=코스닥
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
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    /** 지수 한 시점 스냅샷. 도메인 객체로 격리해 클라이언트 응답 구조 변경에 대응. */
    data class IndexSnapshot(
        val indsCd: String,
        val currentValue: Double,
        val changeRate: Double,
    )
}
