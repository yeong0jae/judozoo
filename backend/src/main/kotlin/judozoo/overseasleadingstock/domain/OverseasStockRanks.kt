package judozoo.overseasleadingstock.domain

import judozoo.library.Ranking

/**
 * 거래대금 상위 풀 — 그 안에서 "주도주다움"으로 다시 세운다.
 *
 * 국내 주도주와 같은 규칙이다. 규칙은 [Ranking]이 갖고, 무엇이 두 축인지만 각자 정한다.
 */
class OverseasStockRanks(private val stocks: List<OverseasStockRank>) {

    /** 거래대금·등락률 두 축이 모두 높은 순으로 [count]개. 오른 종목만 본다. */
    fun leaders(count: Int): List<OverseasStockRank> =
        Ranking.topBalanced(stocks.filter { it.rate > 0 }, { it.tradingValue }, { it.rate }, count, TRADING_VALUE_WEIGHT)

    companion object {
        /**
         * 주도주 점수에서 거래대금 축에 주는 무게(나머지 0.45는 등락률).
         * **국내(0.65)보다 낮다** — 미국 풀은 거래대금 편차가 훨씬 커서 같은 값이면 크게 오른 종목이 대형주에 묻힌다.
         * 0.58 근처는 피한다 — 대형주 서넛이 촘촘히 몰려 시세가 조금만 움직여도 순위가 뒤집힌다.
         */
        const val TRADING_VALUE_WEIGHT = 0.55
    }
}
