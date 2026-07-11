package at.backend.platform.kiwoom.client

import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
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

    /**
     * [mrktTp] 0=코스피, 1=코스닥. [baseDt] 기준일자(YYYYMMDD) — null이면 당일 누적(라이브), 지정 시 그 날 순매수.
     * 종합 업종 행이 없거나 실패 시 null. 라이브(폴러·상세 패널)는 캐시 공유, 일자별은 (시장,일자)로 캐시.
     */
    @Cacheable("sectorNetBuy", key = "#mrktTp + '|' + (#baseDt ?: '')", unless = "#result == null")
    fun fetchSectorNetBuy(mrktTp: String, baseDt: String? = null): SectorInvestorNetBuy? {
        try {
            val token = authClient.getAccessToken()

            val response = kiwoomRestClient.post()
                .uri("/api/dostk/sect")
                .header("authorization", "Bearer $token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("api-id", "ka10051")
                .body(
                    buildMap {
                        put("mrkt_tp", mrktTp)   // 0=코스피, 1=코스닥
                        put("amt_qty_tp", "0")   // 0=금액(억원)
                        put("stex_tp", "3")      // 3=통합(KRX+NXT)
                        if (baseDt != null) put("base_dt", baseDt) // 기준일자 YYYYMMDD
                    },
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
                otherCorpEok = parseSignedLong(total.etc_corp_netprps),
                financialInvestmentEok = parseSignedLong(total.sc_netprps),
                trustEok = parseSignedLong(total.invtrt_netprps),
                pensionFundEok = parseSignedLong(total.endw_netprps),
                privateEquityEok = parseSignedLong(total.samo_fund_netprps),
                insuranceEok = parseSignedLong(total.insrnc_netprps),
                bankEok = parseSignedLong(total.bank_netprps),
                otherFinanceEok = parseSignedLong(total.jnsinkm_netprps),
                // ka10051의 cur_prc/flu_rt는 소수점 빠진 정수(×100) — 2653.81이 "+265381", 3.52%가 "352".
                indexValue = kotlin.math.abs(parseSignedLong(total.cur_prc)) / 100.0,
                changeRate = parseSignedLong(total.flu_rt) / 100.0,
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
        val cur_prc: String? = null,        // 지수 현재가(×100 정수, 부호 포함)
        val flu_rt: String? = null,         // 등락률(×100 정수, 부호 포함)
        val frgnr_netprps: String? = null,  // 외국인 순매수
        val orgn_netprps: String? = null,   // 기관계 순매수
        val ind_netprps: String? = null,    // 개인 순매수
        val etc_corp_netprps: String? = null, // 기타법인
        val sc_netprps: String? = null,     // 금융투자(증권)
        val invtrt_netprps: String? = null, // 투신
        val endw_netprps: String? = null,   // 연기금(기금)
        val samo_fund_netprps: String? = null, // 사모펀드
        val insrnc_netprps: String? = null, // 보험
        val bank_netprps: String? = null,   // 은행
        val jnsinkm_netprps: String? = null, // 종금(기타금융)
    )

    /** 한 시장의 투자자별 당일 누적 순매수(억원, 양수=순매수/음수=순매도) + 기관 세부 + 지수값/등락률. */
    data class SectorInvestorNetBuy(
        val foreignEok: Long,
        val institutionEok: Long,
        val individualEok: Long,
        val otherCorpEok: Long,
        // 기관 세부 (기관계 = 아래 합계 근사)
        val financialInvestmentEok: Long,
        val trustEok: Long,
        val pensionFundEok: Long,
        val privateEquityEok: Long,
        val insuranceEok: Long,
        val bankEok: Long,
        val otherFinanceEok: Long,
        val indexValue: Double,
        val changeRate: Double,
    )
}
