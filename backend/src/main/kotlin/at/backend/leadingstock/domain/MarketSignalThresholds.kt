package at.backend.leadingstock.domain

import at.backend.stock.domain.Market

/**
 * 시장별 투자자 순매수 시그널의 단계 크기와 경계 완충(억원 단위).
 * 코스피는 1조(=10,000억), 코스닥은 1,000억마다 한 단계. 완충은 단계의 10%로,
 * 경계 부근 잔떨림에서 같은 단계가 반복 발화하는 걸 막는다(데드밴드).
 */
object MarketSignalThresholds {

    /** 한 단계 크기(억원). */
    fun stepEok(market: Market): Long = when (market) {
        Market.KOSPI -> 10_000L
        Market.KOSDAQ -> 1_000L
    }

    /** 경계 완충(억원) — 단계 크기의 10%. */
    fun bufferEok(market: Market): Long = stepEok(market) / 10
}
