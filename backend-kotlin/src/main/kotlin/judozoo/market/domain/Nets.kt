package judozoo.market.domain

/**
 * 현물 투자자별 순매수(억원) 묶음.
 *
 * **핵심 개념**: 키움·KIS 투자자 API는 모두 "당일 누적"을 준다. 세션(오전/오후/마감) 순매수는 값을 직접 주는 API가 없어
 * 폴러가 찍어둔 경계 스냅샷끼리 빼서 만든다. 그래서 뺄셈을 지원한다.
 */
data class Nets(
    val individual: Long,
    val foreign: Long,
    val institution: Long,
    val otherCorp: Long,
    val breakdown: SessionOrg,
) {
    operator fun minus(o: Nets) = Nets(individual - o.individual, foreign - o.foreign, institution - o.institution, otherCorp - o.otherCorp, breakdown - o.breakdown)
}

/** 세션 기관 세부(억원) — 표시 순서: 금융투자·보험·기타금융·투신·사모펀드·연기금등·은행. */
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
