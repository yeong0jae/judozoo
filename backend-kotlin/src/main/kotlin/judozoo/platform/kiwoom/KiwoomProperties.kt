package judozoo.platform.kiwoom

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.kiwoom.*`.
 *
 * 조회 한도는 초당 5건(09~10시 3건)이다. 공존하는 동안(028) Python 몫(`KIWOOM_*_PERMITS_PER_SECOND`)과 합쳐 그 아래여야 한다.
 */
@ConfigurationProperties("judozoo.kiwoom")
data class KiwoomProperties(
    val baseUrl: String = "https://api.kiwoom.com",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 1,
    val peakQueryPermitsPerSecond: Int = 1,
)
