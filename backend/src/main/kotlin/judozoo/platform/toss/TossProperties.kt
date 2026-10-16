package judozoo.platform.toss

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.toss.*`.
 *
 * 캔들(MARKET_DATA_CHART)은 초당 20건, 지수 캔들(MARKET_INDICATOR_CHART)은 초당 5건이다 — 각각 4건·1건 여유를 둔다.
 */
@ConfigurationProperties("judozoo.toss")
data class TossProperties(
    val baseUrl: String = "https://openapi.tossinvest.com",
    val clientId: String = "",
    val clientSecret: String = "",
    val chartPermitsPerSecond: Int = 16,
    val indicatorChartPermitsPerSecond: Int = 4,
)
