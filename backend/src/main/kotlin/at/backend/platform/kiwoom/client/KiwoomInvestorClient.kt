package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * 종목별 일자별 투자자·기관 순매매 추이. ka10059 (종목별투자자기관별요청)
 * path: /api/dostk/stkinfo. 응답 단위는 백만원(amt_qty_tp=1).
 *
 * 키움 거래소 구분은 종목코드 suffix로 함:
 *   - "005930"     → SOR 통합 (= KRX + NXT)
 *   - "005930_NX"  → NXT 단독
 *   - "005930_AL"  → SOR 통합 (기본의 별칭)
 */
@Component
class KiwoomInvestorClient(
    private val kiwoomRestClient: RestClient,
    @Suppress("unused") private val properties: KiwoomApiProperties,
    private val authClient: KiwoomAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchInvestorTrend(stockCode: String): List<InvestorTrendDay> {
        try {
            val token = authClient.getAccessToken()
            val today = LocalDate.now().toString().replace("-", "")

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/stkinfo")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10059")
                .body(
                    mapOf(
                        "dt" to today,
                        "stk_cd" to stockCode,
                        "amt_qty_tp" to "1",
                        "trde_tp" to "0",
                        "unit_tp" to "1000",
                    ),
                )
                .retrieve()
                .body(InvestorTrendResponse::class.java)
                ?: return emptyList()

            if (response.return_code != null && response.return_code != 0) {
                log.error(
                    "ka10059 error. Code: {}, Message: {}",
                    response.return_code,
                    response.return_msg,
                )
                return emptyList()
            }

            return response.stk_invsr_orgn?.map { it.toDay() } ?: emptyList()
        } catch (e: Exception) {
            log.error("Failed to fetch investor trend for {}", stockCode, e)
            return emptyList()
        }
    }

    /** "+1234", "-1234" 형식 부호 변종 흡수 → Long */
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

    // --- Response DTOs (raw) ---

    data class InvestorTrendResponse(
        val stk_invsr_orgn: List<InvestorTrendItem>? = null,
        val return_code: Int? = null,
        val return_msg: String? = null,
    )

    data class InvestorTrendItem(
        val dt: String? = null,
        val cur_prc: String? = null,
        val ind_invsr: String? = null,    // 개인
        val frgnr_invsr: String? = null,  // 외국인
        val orgn: String? = null,         // 기관 합계
        val fnnc_invt: String? = null,    // 금융투자
        val insrnc: String? = null,       // 보험
        val invtrt: String? = null,       // 투자신탁
        val etc_fnnc: String? = null,     // 기타금융
        val bank: String? = null,         // 은행
        val penfnd_etc: String? = null,   // 연기금
        val samo_fund: String? = null,    // 사모펀드
        val etc_corp: String? = null,     // 기타법인
    )

    private fun InvestorTrendItem.toDay() = InvestorTrendDay(
        date = dt?.let {
            "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}"
        } ?: "",
        individualNet = parseSignedLong(ind_invsr),
        foreignNet = parseSignedLong(frgnr_invsr),
        institutionNet = parseSignedLong(orgn),
        otherCorpNet = parseSignedLong(etc_corp),
        financialInvestmentNet = parseSignedLong(fnnc_invt),
        insuranceNet = parseSignedLong(insrnc),
        otherFinanceNet = parseSignedLong(etc_fnnc),
        trustNet = parseSignedLong(invtrt),
        privateEquityNet = parseSignedLong(samo_fund),
        pensionFundNet = parseSignedLong(penfnd_etc),
        bankNet = parseSignedLong(bank),
    )

    /** 도메인 객체로 격리. 모든 값은 백만원 단위 순매수 (양수=순매수, 음수=순매도). */
    data class InvestorTrendDay(
        val date: String,         // ISO yyyy-MM-dd
        val individualNet: Long,  // 개인 순매수
        val foreignNet: Long,     // 외국인 순매수
        val institutionNet: Long, // 기관 합계 순매수
        val otherCorpNet: Long = 0,
        // 기관 세부 — 기관 합계의 내역
        val financialInvestmentNet: Long = 0,
        val insuranceNet: Long = 0,
        val otherFinanceNet: Long = 0,
        val trustNet: Long = 0,
        val privateEquityNet: Long = 0,
        val pensionFundNet: Long = 0,
        val bankNet: Long = 0,
    )
}
