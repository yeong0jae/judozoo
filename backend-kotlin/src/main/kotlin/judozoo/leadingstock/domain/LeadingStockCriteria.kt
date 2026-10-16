package judozoo.leadingstock.domain

/** 주도주 후보 필터 기준값. Python `LeadingStockCriteria`의 기본값과 같다 — 운영도 이 값이다. */
data class LeadingStockCriteria(
    /** 억원 */
    val minMarketCap: Long = 3000,
    val maxTradingValueRank: Int = 35,
    /** % */
    val minDailyPriceChangeRate: Double = 7.0,
    /** 60봉 고가 대비 % */
    val maxHighPositionDropRate: Double = -5.0,
    /** 백만원 — 프로그램이 순매수인 종목만 */
    val minProgramNetBuy: Long = 0,
    val maxPrevCloseChangeRate: Double = 25.0,
    val maxOpeningPriceChangeRate: Double = 7.0,
)
