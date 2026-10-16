package judozoo.platform.kiwoom

import judozoo.library.KST
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class KiwoomInvestor(private val client: KiwoomClient, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchInvestorTrend(stockCode: String): List<InvestorTrendDay> = try {
        val body = client.query(
            STOCK_INFO_URL, "ka10059",
            mapOf(
                "dt" to LocalDate.now(clock.withZone(KST)).format(DateTimeFormatter.BASIC_ISO_DATE),
                "stk_cd" to stockCode,
                "amt_qty_tp" to "1",
                "trde_tp" to "0",
                "unit_tp" to "1000",
            ),
        ).body
        body["stk_invsr_orgn"]?.values()?.map(::toDay).orEmpty()
    } catch (e: Exception) {
        log.error("키움 종목 수급 조회 실패 stk_cd={}", stockCode, e)
        emptyList()
    }

    private fun toDay(item: JsonNode): InvestorTrendDay {
        fun signed(field: String) = item[field]?.asString().toSignedLong()
        return InvestorTrendDay(
            date = parseDate(item["dt"]?.asString()),
            individualNet = signed("ind_invsr"),
            foreignNet = signed("frgnr_invsr"),
            institutionNet = signed("orgn"),
            otherCorpNet = signed("etc_corp"),
            financialInvestmentNet = signed("fnnc_invt"),
            insuranceNet = signed("insrnc"),
            otherFinanceNet = signed("etc_fnnc"),
            trustNet = signed("invtrt"),
            privateEquityNet = signed("samo_fund"),
            pensionFundNet = signed("penfnd_etc"),
            bankNet = signed("bank"),
        )
    }

    companion object {
        private const val STOCK_INFO_URL = "/api/dostk/stkinfo"

        private fun parseDate(value: String?): LocalDate? =
            value?.trim()?.takeIf { it.length == 8 }?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
    }
}
