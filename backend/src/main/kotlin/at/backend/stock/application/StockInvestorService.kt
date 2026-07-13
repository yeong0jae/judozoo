package at.backend.stock.application

import at.backend.platform.kiwoom.client.KiwoomInvestorClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 종목별 일별 투자자 순매수 — 키움 ka10059. 시장(코스피/코스닥) 표와 같은 구성으로 준다.
 * ka10059는 백만원 단위라 억원으로 환산한다(시장 수급 표와 단위를 맞춘다).
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
        individualEok = individualNet.toEok(),
        foreignEok = foreignNet.toEok(),
        institutionEok = institutionNet.toEok(),
        otherCorpEok = otherCorpNet.toEok(),
        breakdown = StockOrgBreakdown(
            financialInvestmentEok = financialInvestmentNet.toEok(),
            trustEok = trustNet.toEok(),
            pensionFundEok = pensionFundNet.toEok(),
            privateEquityEok = privateEquityNet.toEok(),
            insuranceEok = insuranceNet.toEok(),
            bankEok = bankNet.toEok(),
            otherFinanceEok = otherFinanceNet.toEok(),
        ),
    )

    /** 백만원 → 억원. */
    private fun Long.toEok() = this / 100
}

/** 기관 세부 순매수(억원) — 시장 수급 표와 같은 7종. */
data class StockOrgBreakdown(
    val financialInvestmentEok: Long,
    val trustEok: Long,
    val pensionFundEok: Long,
    val privateEquityEok: Long,
    val insuranceEok: Long,
    val bankEok: Long,
    val otherFinanceEok: Long,
)

/** 하루치 종목 투자자 순매수(억원). */
data class StockInvestorDay(
    val date: LocalDate,
    val individualEok: Long,
    val foreignEok: Long,
    val institutionEok: Long,
    val otherCorpEok: Long,
    val breakdown: StockOrgBreakdown,
)
