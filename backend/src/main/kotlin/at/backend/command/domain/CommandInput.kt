package at.backend.command.domain

import java.math.BigDecimal

data class CommandInput(
    val stockCode: String,
    val perBuyAmount: Long,
    val buyIntervalMin: Int,
    val splitSellRatio: BigDecimal,
    val midwayProfitPct: BigDecimal,
    val breakevenThresholdPct: BigDecimal,
    val stopLossPct: BigDecimal,
)
