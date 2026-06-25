package at.backend.platform.kiwoom.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 업종별 투자자 순매수 조회. ka10051 (업종별투자자순매수요청), path: /api/dostk/sect.
 * 코스피/코스닥 "종합" 업종 행에서 외국인·기관·개인 당일 누적 순매수를 읽는다.
 * amt_qty_tp=0(금액) 기준이라 순매수 단위는 **억원**, stex_tp=3(통합)으로 KRX+NXT를 합산한다.
 */
@Component
class KiwoomSectorInvestorClient(
    private val kiwoomRestClient: RestClient,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [mrktTp] 0=코스피, 1=코스닥. 종합 업종 행이 없거나 실패 시 null. */
    fun fetchSectorNetBuy(mrktTp: String): SectorInvestorNetBuy? {
        try {
            val token = authClient.getAccessToken()

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/sect")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10051")
                .body(
                    mapOf(
                        "mrkt_tp" to mrktTp,   // 0=코스피, 1=코스닥
                        "amt_qty_tp" to "0",   // 0=금액(억원)
                        "stex_tp" to "3",      // 3=통합(KRX+NXT)
                    ),
                )
                .retrieve()
                .body(SectorNetBuyResponse::class.java)
                ?: return null

            if (response.return_code != null && response.return_code != 0) {
                log.error("ka10051 error. Code: {}, Message: {}", response.return_code, response.return_msg)
                return null
            }

            // 첫 행이 "종합(KOSPI)"/"종합(KOSDAQ)" — 없으면 리스트 첫 행으로 폴백.
            val total = response.inds_netprps
                ?.firstOrNull { it.inds_nm?.contains("종합") == true }
                ?: response.inds_netprps?.firstOrNull()
                ?: return null

            return SectorInvestorNetBuy(
                foreignEok = parseSignedLong(total.frgnr_netprps),
                institutionEok = parseSignedLong(total.orgn_netprps),
                individualEok = parseSignedLong(total.ind_netprps),
            )
        } catch (e: Exception) {
            log.error("Failed to fetch sector net buy mrkt_tp={}", mrktTp, e)
            return null
        }
    }

    /** "+255", "-622", "1234-" 등 부호 변종 흡수 → Long(억원). */
    private fun parseSignedLong(value: String?): Long {
        val s = value?.trim() ?: return 0L
        if (s.isEmpty()) return 0L
        val normalized = when {
            s.startsWith("+") -> s.substring(1)
            s.endsWith("-") -> "-" + s.dropLast(1)
            else -> s
        }
        return normalized.toLongOrNull() ?: 0L
    }

    data class SectorNetBuyResponse(
        val inds_netprps: List<SectorNetBuyItem>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class SectorNetBuyItem(
        val inds_cd: String? = null,
        val inds_nm: String? = null,
        val frgnr_netprps: String? = null, // 외국인 순매수
        val orgn_netprps: String? = null,  // 기관계 순매수
        val ind_netprps: String? = null,   // 개인 순매수
    )

    /** 한 시장의 투자자별 당일 누적 순매수(억원, 양수=순매수/음수=순매도). */
    data class SectorInvestorNetBuy(
        val foreignEok: Long,
        val institutionEok: Long,
        val individualEok: Long,
    )
}
