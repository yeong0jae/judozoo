package judozoo.market.domain

/** 선물 투자자별 순매수(계약) 묶음. [Nets]와 같은 이유로 뺄셈을 지원한다. */
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

/** 세션 기관 세부(계약) — 표시 순서: 증권·보험·종금·투신·사모펀드·기금·은행·기타단체. */
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
