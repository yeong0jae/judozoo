package judozoo.stock.application

import judozoo.library.TtlCache
import judozoo.platform.kiwoom.KiwoomInvestor
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate

/** 종목 투자자 순매수 — 시황분석 종목 상세용. */
@Service
class StockInvestorService(private val kiwoom: KiwoomInvestor) {

    /** 기관 세부 순매수(백만원) — 시장 수급 표와 같은 7종. */
    data class OrgBreakdown(
        val financialInvestmentMillion: Long,
        val trustMillion: Long,
        val pensionFundMillion: Long,
        val privateEquityMillion: Long,
        val insuranceMillion: Long,
        val bankMillion: Long,
        val otherFinanceMillion: Long,
    )

    /** 하루치 종목 투자자 순매수(백만원). */
    data class InvestorDay(
        val date: LocalDate,
        val individualMillion: Long,
        val foreignMillion: Long,
        val institutionMillion: Long,
        val otherCorpMillion: Long,
        val breakdown: OrgBreakdown,
    )

    private val cache = TtlCache<String, List<InvestorDay>>("stockInvestorDaily", Duration.ofSeconds(60), maxSize = 100, skipIf = { it.isEmpty() })

    /**
     * 최근 [count]거래일. **백만원 단위를 그대로 준다** — 억으로 반올림하면 작은 수급(기관 세부 등)이 0으로 뭉개진다.
     * 표시 단위는 각 화면이 정한다. 날짜 형식이 어긋난 행은 버린다.
     */
    fun investorDailyHistory(stockCode: String, count: Int): List<InvestorDay> =
        cache.get("$stockCode:$count") {
            kiwoom.fetchInvestorTrend(stockCode).take(count).mapNotNull { day ->
                val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
                InvestorDay(
                    date = date,
                    individualMillion = day.individualNet,
                    foreignMillion = day.foreignNet,
                    institutionMillion = day.institutionNet,
                    otherCorpMillion = day.otherCorpNet,
                    breakdown = OrgBreakdown(
                        financialInvestmentMillion = day.financialInvestmentNet,
                        trustMillion = day.trustNet,
                        pensionFundMillion = day.pensionFundNet,
                        privateEquityMillion = day.privateEquityNet,
                        insuranceMillion = day.insuranceNet,
                        bankMillion = day.bankNet,
                        otherFinanceMillion = day.otherFinanceNet,
                    ),
                )
            }
        }
}
