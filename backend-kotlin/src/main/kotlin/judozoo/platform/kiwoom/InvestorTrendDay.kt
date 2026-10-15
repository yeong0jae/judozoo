package judozoo.platform.kiwoom

/** 종목 하루치 투자자별 순매수(백만원). 양수=순매수, 음수=순매도. */
data class InvestorTrendDay(
    /** ISO yyyy-MM-dd. 형식이 어긋나면 빈 문자열. */
    val date: String,
    val individualNet: Long,
    val foreignNet: Long,
    val institutionNet: Long,
    val otherCorpNet: Long = 0,
    // 기관 세부 — 기관 합계의 내역
    val financialInvestmentNet: Long = 0,
    val insuranceNet: Long = 0,
    val otherFinanceNet: Long = 0,
    val trustNet: Long = 0,
    val privateEquityNet: Long = 0,
    val pensionFundNet: Long = 0,
    val bankNet: Long = 0,
)
