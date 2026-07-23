package at.backend.stock.application

import at.backend.platform.kiwoom.client.KiwoomInvestorClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 종목별 일별 투자자 순매수 — 키움 ka10059. ka10059 원본 단위인 백만원을 그대로 준다.
 * 종목 단위는 억으로 반올림하면 작은 수급(기관 세부 등)이 0으로 뭉개져 백만원으로 노출한다.
 * 표시단(억/조 vs 백만원)은 각 화면이 결정한다.
 */
@Service
class StockInvestorService(
    private val investorClient: KiwoomInvestorClient,
) {

    @Cacheable("stockInvestorDaily", key = "#stockCode + ':' + #count", unless = "#result.isEmpty()")
    fun dailyHistory(stockCode: String, count: Int): List<StockInvestorDay> =
        investorClient.fetchInvestorTrend(stockCode)
            .take(count)
            .map { it.toDay() }

    private fun KiwoomInvestorClient.InvestorTrendDay.toDay() = StockInvestorDay(
        date = LocalDate.parse(date),
        individualMillion = individualNet,
        foreignMillion = foreignNet,
        institutionMillion = institutionNet,
        otherCorpMillion = otherCorpNet,
        breakdown = StockOrgBreakdown(
            financialInvestmentMillion = financialInvestmentNet,
            trustMillion = trustNet,
            pensionFundMillion = pensionFundNet,
            privateEquityMillion = privateEquityNet,
            insuranceMillion = insuranceNet,
            bankMillion = bankNet,
            otherFinanceMillion = otherFinanceNet,
        ),
    )
}

/** 기관 세부 순매수(백만원) — 시장 수급 표와 같은 7종. */
data class StockOrgBreakdown(
    val financialInvestmentMillion: Long,
    val trustMillion: Long,
    val pensionFundMillion: Long,
    val privateEquityMillion: Long,
    val insuranceMillion: Long,
    val bankMillion: Long,
    val otherFinanceMillion: Long,
)

/** 하루치 종목 투자자 순매수(백만원). */
data class StockInvestorDay(
    val date: LocalDate,
    val individualMillion: Long,
    val foreignMillion: Long,
    val institutionMillion: Long,
    val otherCorpMillion: Long,
    val breakdown: StockOrgBreakdown,
)
