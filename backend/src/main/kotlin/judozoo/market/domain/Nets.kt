package judozoo.market.domain

data class Nets(
    val individual: Long,
    val foreign: Long,
    val institution: Long,
    val otherCorp: Long,
    val breakdown: SessionOrg,
) {
    operator fun minus(o: Nets) = Nets(individual - o.individual, foreign - o.foreign, institution - o.institution, otherCorp - o.otherCorp, breakdown - o.breakdown)
}

data class SessionOrg(
    val financialInvestmentEok: Long,
    val insuranceEok: Long,
    val otherFinanceEok: Long,
    val trustEok: Long,
    val privateEquityEok: Long,
    val pensionFundEok: Long,
    val bankEok: Long,
) {
    operator fun minus(o: SessionOrg) = SessionOrg(
        financialInvestmentEok - o.financialInvestmentEok, insuranceEok - o.insuranceEok, otherFinanceEok - o.otherFinanceEok,
        trustEok - o.trustEok, privateEquityEok - o.privateEquityEok, pensionFundEok - o.pensionFundEok, bankEok - o.bankEok,
    )
}
