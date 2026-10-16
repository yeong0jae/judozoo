package judozoo.leadingstock.domain

/**
 * 주도주 후보 종목의 시점 스냅샷. 키움으로 수집한 시세·랭킹·기본 정보.
 *
 * 상장 종목 카탈로그(`stock`)와는 별개 개념이라 "Snapshot"으로 구분한다.
 */
data class LeadingStockSnapshot(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Long,
    val priceChangeRate: Double,
    val tradingValueRank: Int,
    val accumulatedTradingValue: Long,
    val accumulatedVolume: Long? = null,
    val marketCap: Long = 0,
    val openingPrice: Long = 0,
    val previousClose: Long = 0,
    val highPrice: Long = 0,
    val lowPrice: Long = 0,
    val programNetBuy: Long = 0,
    /**
     * 상한가로 잠겼는가. **거래소가 준 판정**이라 등락률로 대신 가릴 수 없다 —
     * 신규상장 종목은 제한폭이 없어 +150%로도 상한가가 아니다.
     */
    val limitUp: Boolean = false,
    /**
     * 오늘이 제한폭이 넓은 날(상장 첫날)인가. 한 번이라도 +30%를 넘었으면 **그날 내내** 참이다 —
     * 밀려 내려온 신규상장 종목이 "+26% 급등주"로 순위가 튀지 않게.
     */
    val wideLimitDay: Boolean = false,
) {
    /** 일반 종목은 닿을 수 없는 폭(+30% 초과)으로 올랐는가 — 제한폭이 넓은 날이라는 표시다. */
    val exceedsNormalLimit: Boolean get() = priceChangeRate > NORMAL_LIMIT_RATE

    /** 제한폭이 넓은 날(상장 첫날)인가 — 오늘 넘은 적이 있거나 지금 넘어 있다. */
    val isWideLimitDay: Boolean get() = wideLimitDay || exceedsNormalLimit

    /** 주도주 순위를 매길 때 쓰는 거래대금. 제한폭이 넓은 날이면 부풀려진 만큼 나눈다. 화면에는 원래 값을 보인다. */
    val tradingValueForRanking: Double
        get() = if (isWideLimitDay) accumulatedTradingValue / LISTING_TRADING_VALUE_DIVISOR else accumulatedTradingValue.toDouble()

    /**
     * 주도주 순위를 매길 때 쓰는 등락률. 제한폭이 넓은 날이면 [LISTING_RATE_DIVISOR]로 나눈다.
     *
     * 상장 첫날 등락률은 공모가 대비라 +30%를 넘는 한 늘 등수 1등이다 — 일반 종목의 하루 폭 안으로 끌어내린다.
     */
    val rateForRanking: Double
        get() = if (isWideLimitDay) priceChangeRate / LISTING_RATE_DIVISOR else priceChangeRate

    companion object {
        /** 일반 종목의 가격제한폭(%). 이보다 크게 움직였다면 제한폭이 넓은 날이다 — 상장 첫날이 대표적이다. */
        const val NORMAL_LIMIT_RATE = 30.0

        /** 제한폭이 넓은 날 순위용 등락률을 이만큼 나눈다. 점수가 등수를 써서 ÷10으로는 여전히 위에 남았다. */
        const val LISTING_RATE_DIVISOR = 20.0

        /** 제한폭이 넓은 날 순위용 거래대금을 이만큼 나눈다. 돈이 몰린 것 자체는 사실이라 크게 깎지 않는다(÷3이면 하위권). */
        const val LISTING_TRADING_VALUE_DIVISOR = 2.0
    }
}
