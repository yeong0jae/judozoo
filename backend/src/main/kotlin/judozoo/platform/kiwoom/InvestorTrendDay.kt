package judozoo.platform.kiwoom

import java.time.LocalDate

data class InvestorTrendDay(
    val date: LocalDate?,
    val individualNet: Long,
    val foreignNet: Long,
    val institutionNet: Long,
    val otherCorpNet: Long = 0,
    val financialInvestmentNet: Long = 0,
    val insuranceNet: Long = 0,
    val otherFinanceNet: Long = 0,
    val trustNet: Long = 0,
    val privateEquityNet: Long = 0,
    val pensionFundNet: Long = 0,
    val bankNet: Long = 0,
)
