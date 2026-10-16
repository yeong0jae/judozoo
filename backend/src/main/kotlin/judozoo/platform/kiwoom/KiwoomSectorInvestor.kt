package judozoo.platform.kiwoom

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.abs

@Component
class KiwoomSectorInvestor(private val client: KiwoomClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchSectorNetBuy(marketType: String): SectorInvestorNetBuy? = try {
        val rows = client.query(
            SECT_URL, "ka10051",
            mapOf("mrkt_tp" to marketType, "amt_qty_tp" to "0", "stex_tp" to "3"),
        ).body["inds_netprps"]?.toList().orEmpty()

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
            indexValue = abs(signed("cur_prc")) / 100.0,
            changeRate = signed("flu_rt") / 100.0,
        )
    }

    companion object {
        private const val SECT_URL = "/api/dostk/sect"
    }
}
