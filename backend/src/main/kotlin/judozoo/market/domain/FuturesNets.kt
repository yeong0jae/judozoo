package judozoo.market.domain

data class FuturesNets(
    val foreign: Long,
    val institution: Long,
    val individual: Long,
    val otherCorp: Long,
    val breakdown: FuturesOrg,
) {
    operator fun minus(o: FuturesNets) =
        FuturesNets(foreign - o.foreign, institution - o.institution, individual - o.individual, otherCorp - o.otherCorp, breakdown - o.breakdown)
}

data class FuturesOrg(
    val securities: Long,
    val insurance: Long,
    val merchantBank: Long,
    val trust: Long,
    val privateEquity: Long,
    val fund: Long,
    val bank: Long,
    val otherOrg: Long,
) {
    operator fun minus(o: FuturesOrg) = FuturesOrg(
        securities - o.securities, insurance - o.insurance, merchantBank - o.merchantBank, trust - o.trust,
        privateEquity - o.privateEquity, fund - o.fund, bank - o.bank, otherOrg - o.otherOrg,
    )
}
