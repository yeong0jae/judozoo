package judozoo.platform.toss

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("judozoo.toss")
data class TossProperties(
    val baseUrl: String = "https://openapi.tossinvest.com",
    val clientId: String = "",
    val clientSecret: String = "",
    val chartPermitsPerSecond: Int = 16,
    val indicatorChartPermitsPerSecond: Int = 4,
)
