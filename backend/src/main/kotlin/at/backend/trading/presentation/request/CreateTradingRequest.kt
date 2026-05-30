package at.backend.trading.presentation.request

import at.backend.trading.TradingProperties
import at.backend.trading.domain.TradingInput
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class CreateTradingRequest(
    @field:NotBlank val stockCode: String,
    @field:Positive val perBuyQty: Int,
    val splitSellRatio: BigDecimal? = null,
    val breakevenThresholdPct: BigDecimal? = null,
    val stopLossPct: BigDecimal? = null,
) {
    fun toTradingInput(defaults: TradingProperties) = TradingInput(
        stockCode = stockCode,
        perBuyQty = perBuyQty,
        splitSellRatio = splitSellRatio ?: defaults.defaultSplitSellRatio,
        breakevenThresholdPct = breakevenThresholdPct ?: defaults.defaultBreakevenThresholdPct,
        stopLossPct = stopLossPct ?: defaults.defaultStopLossPct,
    )
}
