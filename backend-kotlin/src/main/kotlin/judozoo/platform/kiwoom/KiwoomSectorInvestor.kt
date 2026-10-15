package judozoo.platform.kiwoom

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.abs

/**
 * 업종별 투자자 순매수 (ka10051).
 *
 * 코스피/코스닥 "종합" 업종 행에서 외국인·기관·개인 당일 누적 순매수를 읽는다.
 * amt_qty_tp=0(금액) 기준이라 순매수 단위는 **억원**, stex_tp=3(통합)으로 KRX+NXT를 합산한다.
 */
@Component
class KiwoomSectorInvestor(private val client: KiwoomClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [marketType] 0=코스피, 1=코스닥. 당일 누적(라이브). 실패하면 null. */
    fun fetchSectorNetBuy(marketType: String): SectorInvestorNetBuy? = try {
        val rows = client.query(
            SECT_URL, "ka10051",
            mapOf("mrkt_tp" to marketType, "amt_qty_tp" to "0", "stex_tp" to "3"),
        ).body["inds_netprps"]?.toList().orEmpty()

        // 첫 행이 "종합(KOSPI)"/"종합(KOSDAQ)" — 없으면 첫 행으로 폴백
        rows.firstOrNull { "종합" in it["inds_nm"]?.asString().orEmpty() }?.let(::toNetBuy)
            ?: rows.firstOrNull()?.let(::toNetBuy)
    } catch (e: Exception) {
        log.error("키움 업종 투자자 순매수 조회 실패 mrkt_tp={}", marketType, e)
        null
    }

    private fun toNetBuy(total: tools.jackson.databind.JsonNode): SectorInvestorNetBuy {
        fun signed(field: String) = total[field]?.asString().toSignedLong()
        return SectorInvestorNetBuy(
            foreignEok = signed("frgnr_netprps"),
            institutionEok = signed("orgn_netprps"),
            individualEok = signed("ind_netprps"),
            otherCorpEok = signed("etc_corp_netprps"),
            financialInvestmentEok = signed("sc_netprps"),
            trustEok = signed("invtrt_netprps"),
            pensionFundEok = signed("endw_netprps"),
            privateEquityEok = signed("samo_fund_netprps"),
            insuranceEok = signed("insrnc_netprps"),
            bankEok = signed("bank_netprps"),
            otherFinanceEok = signed("jnsinkm_netprps"),
            // ka10051의 cur_prc/flu_rt는 **소수점이 빠진 정수(×100)**다 — 2653.81이 "+265381", 3.52%가 "352"
            indexValue = abs(signed("cur_prc")) / 100.0,
            changeRate = signed("flu_rt") / 100.0,
        )
    }

    companion object {
        private const val SECT_URL = "/api/dostk/sect"
    }
}
