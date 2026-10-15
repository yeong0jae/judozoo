package judozoo.platform.kiwoom

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.kiwoom.*`.
 *
 * 한도는 **Python과 나눠 쓴다**(028). 키움 조회 한도는 초당 5건(09~10시 3건)이고, Python
 * `KIWOOM_QUERY_PERMITS_PER_SECOND`·`KIWOOM_PEAK_QUERY_PERMITS_PER_SECOND`와 합쳐 그 아래여야 한다.
 * 기능을 옮길 때마다 Python 몫을 줄이고 여기를 늘린다.
 */
@ConfigurationProperties("judozoo.kiwoom")
data class KiwoomProperties(
    val baseUrl: String = "https://api.kiwoom.com",
    val queryPermitsPerSecond: Int = 1,
    val peakQueryPermitsPerSecond: Int = 1,
)
