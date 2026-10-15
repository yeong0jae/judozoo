package judozoo.platform.toss

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.toss.*`.
 *
 * 한도는 **Python과 나눠 쓴다**(028). 캔들(MARKET_DATA_CHART)은 초당 20건, 지수 캔들(MARKET_INDICATOR_CHART)은
 * 초당 5건이고 Python `TOSS_CHART_PERMITS_PER_SECOND`(16)·`TOSS_INDICATOR_CHART_PERMITS_PER_SECOND`(4)와 합쳐 그 아래여야 한다.
 */
@ConfigurationProperties("judozoo.toss")
data class TossProperties(
    val baseUrl: String = "https://openapi.tossinvest.com",
    val chartPermitsPerSecond: Int = 1,
    val indicatorChartPermitsPerSecond: Int = 1,
)
