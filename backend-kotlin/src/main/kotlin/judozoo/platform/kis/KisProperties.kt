package judozoo.platform.kis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.kis.*`.
 *
 * 한도는 초당 18건이다. 공존하는 동안(028) Python 몫(`KIS_QUERY_PERMITS_PER_SECOND`)과 합쳐 그 아래여야 한다.
 */
@ConfigurationProperties("judozoo.kis")
data class KisProperties(
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 1,
)
