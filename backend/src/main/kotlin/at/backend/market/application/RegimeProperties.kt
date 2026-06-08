package at.backend.market.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalTime

@ConfigurationProperties(prefix = "trading.market.regime")
data class RegimeProperties(
    val pollIntervalMillis: Long,
    val startTime: LocalTime,
    val anchorTime: LocalTime,
    val endTime: LocalTime,
    val basketSize: Int,
    val fetchCount: Int,
    val backfillStockCode: String = "000660",
    val backfillDays: Int = 20,
)
