package judozoo.leadingstock.domain

import judozoo.stock.domain.Market

/**
 * 시장별 투자자 순매수 시그널의 크기(억원). 코스피는 1조, 코스닥은 1,000억마다 한 단계.
 * 완충은 단계의 10%로 경계 잔떨림을 흡수한다. 흐름 전환 임계는 코스닥이 규모가 작아 더 민감하다.
 */
data class MarketSignalScale(val stepEok: Long, val reversalEok: Long) {
    val bufferEok: Long get() = stepEok / 10

    companion object {
        fun of(market: Market): MarketSignalScale = when (market) {
            Market.KOSPI -> MarketSignalScale(stepEok = 10_000, reversalEok = 1_000)
            Market.KOSDAQ -> MarketSignalScale(stepEok = 1_000, reversalEok = 100)
        }
    }
}
