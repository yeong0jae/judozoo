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
)
