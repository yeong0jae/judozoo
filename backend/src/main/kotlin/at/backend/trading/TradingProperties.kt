package at.backend.trading

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val defaultSplitSellRatio: BigDecimal,
    val defaultBreakevenThresholdPct: BigDecimal,
    val defaultStopLossPct: BigDecimal,
    val sellCostRate: BigDecimal,
)
