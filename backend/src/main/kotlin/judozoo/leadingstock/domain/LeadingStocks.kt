package judozoo.leadingstock.domain

import judozoo.library.Ranking

/** 거래대금 상위 풀 — 그 안에서 "주도주다움"으로 다시 세운다. */
class LeadingStocks(private val stocks: List<LeadingStockSnapshot>) {

    /**
     * 거래대금·등락률 두 축이 모두 높은 순으로 [count]개. 상장 첫날은 두 축 모두 보정한 값을 쓴다
     * ([LeadingStockSnapshot.tradingValueForRanking], [LeadingStockSnapshot.rateForRanking]).
     *
     * **오른 종목만 본다.** 돈이 아무리 붙어도 내린 종목은 그날의 주도주가 아니다.
     */
    fun leaders(count: Int): List<LeadingStockSnapshot> = Ranking.topBalanced(
        stocks.filter { it.priceChangeRate > 0 }, { it.tradingValueForRanking }, { it.rateForRanking }, count, TRADING_VALUE_WEIGHT,
    )

    /** 이 풀 안에서 상한가인 종목 — 받은 순서(거래대금 내림차순) 그대로. **점수로 다시 세우지 않는다** — 상한가는 등수가 아니라 "지금 잠겼다"는 상태다. */
    fun limitUps(): List<LeadingStockSnapshot> = stocks.filter { it.limitUp }

    companion object {
        /**
         * 주도주 점수에서 거래대금 축에 주는 무게(나머지는 등락률). 0.5면 두 축이 대등하다.
         * 거래대금 쪽으로 기울여 돈이 실제로 몰린 종목을 위에 세운다. 0.7은 크게 오른 종목이 너무 쉽게 밀렸고,
         * 0.4 아래면 소형 상한가 종목이 대장주를 밀어낸다. 해외는 거래대금 편차가 커서 따로 간다(0.55).
         */
        const val TRADING_VALUE_WEIGHT = 0.65
    }
}
