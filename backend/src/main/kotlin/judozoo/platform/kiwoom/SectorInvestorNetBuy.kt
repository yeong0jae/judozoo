package judozoo.platform.kiwoom

data class SectorInvestorNetBuy(
    val foreignEok: Long,
    val institutionEok: Long,
    val individualEok: Long,
    val otherCorpEok: Long,
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
