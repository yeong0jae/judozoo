package judozoo.stock.application

import judozoo.library.TtlCache
import judozoo.platform.kiwoom.KiwoomInvestor
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate

@Service
class StockInvestorService(private val kiwoom: KiwoomInvestor) {
    data class OrgBreakdown(
        val financialInvestmentMillion: Long,
        val trustMillion: Long,
        val pensionFundMillion: Long,
        val privateEquityMillion: Long,
        val insuranceMillion: Long,
        val bankMillion: Long,
        val otherFinanceMillion: Long,
    )

    data class InvestorDay(
        val date: LocalDate,
        val individualMillion: Long,
        val foreignMillion: Long,
        val institutionMillion: Long,
        val otherCorpMillion: Long,
        val breakdown: OrgBreakdown,
    )

    private val cache = TtlCache<String, List<InvestorDay>>("stockInvestorDaily", Duration.ofSeconds(60), maxSize = 100, skipIf = { it.isEmpty() })

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
