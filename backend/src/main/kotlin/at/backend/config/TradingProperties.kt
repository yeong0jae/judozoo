package at.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val marketCloseTime: String,
    val defaultBuyIntervalMin: Int,
    val defaultSplitSellRatio: BigDecimal,
    val defaultMidwayProfitPct: BigDecimal,
    val defaultBreakevenThresholdPct: BigDecimal,
    val defaultStopLossPct: BigDecimal,
    val sellCostRate: BigDecimal,
)
