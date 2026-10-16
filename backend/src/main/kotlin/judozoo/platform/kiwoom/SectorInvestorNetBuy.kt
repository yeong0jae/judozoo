package judozoo.platform.kiwoom

/** 한 시장의 투자자별 당일 누적 순매수(억원, 양수=순매수) + 기관 세부 + 지수값/등락률. */
data class SectorInvestorNetBuy(
    val foreignEok: Long,
    val institutionEok: Long,
    val individualEok: Long,
    val otherCorpEok: Long,
    // 기관 세부 (기관계 ≈ 아래 합계)
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
