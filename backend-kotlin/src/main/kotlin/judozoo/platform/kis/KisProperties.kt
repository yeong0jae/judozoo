package judozoo.platform.kis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.kis.*`.
 *
 * 한도는 **Python과 나눠 쓴다**(028). KIS 한도는 초당 18건이고 Python `KIS_QUERY_PERMITS_PER_SECOND`(기본 15)와
 * 합쳐 그 아래여야 한다. 기능을 옮길 때마다 Python 몫을 줄이고 여기를 늘린다.
 */
@ConfigurationProperties("judozoo.kis")
data class KisProperties(
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 1,
)
