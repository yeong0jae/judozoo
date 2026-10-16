package judozoo.platform.toss

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.toss.*`.
 *
 * 캔들(MARKET_DATA_CHART)은 초당 20건, 지수 캔들(MARKET_INDICATOR_CHART)은 초당 5건이다.
 * 공존하는 동안(028) Python 몫(`TOSS_*_PERMITS_PER_SECOND`)과 합쳐 그 아래여야 한다.
 */
@ConfigurationProperties("judozoo.toss")
data class TossProperties(
    val baseUrl: String = "https://openapi.tossinvest.com",
    val clientId: String = "",
    val clientSecret: String = "",
    val chartPermitsPerSecond: Int = 1,
    val indicatorChartPermitsPerSecond: Int = 1,
)
