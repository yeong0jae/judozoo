package at.backend.overseasleadingstock.application

import at.backend.leadingstock.domain.SpikeDirection

/** 폴러 한 폴 시점의 후보 종목 측정값 — 시그널 전이 판정과 이벤트 적재에 함께 쓰인다. */
data class OverseasCandidateReading(
    val exchange: String,
    val symbol: String,
    val name: String,
    val price: Double,
    val rate: Double,
    val tradingValue: Double,
    val gapRate: Double?,
    val peakPrice: Double?,
    val spikeRatio: Double?,
    val minuteTradingValue: Double?,
    val spikeDirection: SpikeDirection?,
    val ma20CrossedUp: Boolean?, // 최신 확정 5분봉이 20이평을 아래→위로 돌파한 봉인지. 확정 봉 부족이면 null
    val ma20BelowBand: Boolean?, // 최신 확정 5분봉 종가가 20이평보다 마진 이상 아래인지. 돌림 재무장 신호
    val ma20: Double?, // 그 시점 5분봉 20이평값(달러). ma20CrossedUp이 null이면 null
)
