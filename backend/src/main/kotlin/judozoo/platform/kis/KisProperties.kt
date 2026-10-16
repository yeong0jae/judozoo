package judozoo.platform.kis

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("judozoo.kis")
data class KisProperties(
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 15,
)
