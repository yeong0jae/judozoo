package at.backend.trading.domain.signal

import at.backend.trading.domain.cycle.CycleState

data class CycleSnapshot(
    val state: CycleState,
    val holdingQty: Int,
    val buyPrice: Int,
    val tpStagesFired: Int,
    val breakevenArmed: Boolean,
    val trendBreakArmed: Boolean,
    val buyAttempt: Int,
    val stopLossPct: Double,        // 음수 (예: -0.02)
    val midwayProfitPct: Double,    // 양수 % (예: 3.0)
)
