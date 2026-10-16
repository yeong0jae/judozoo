package judozoo.platform.kiwoom

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.kiwoom.*`.
 *
 * 조회 한도는 초당 5건(09~10시 3건)이다.
 */
@ConfigurationProperties("judozoo.kiwoom")
data class KiwoomProperties(
    val baseUrl: String = "https://api.kiwoom.com",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 5,
    val peakQueryPermitsPerSecond: Int = 3,
)
