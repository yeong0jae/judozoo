package judozoo.platform.kiwoom

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("judozoo.kiwoom")
data class KiwoomProperties(
    val baseUrl: String = "https://api.kiwoom.com",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 5,
    val peakQueryPermitsPerSecond: Int = 3,
)
