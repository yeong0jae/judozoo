package judozoo.platform.kis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `judozoo.kis.*`.
 *
 * 한도는 초당 18건이다 — 3건 여유를 둔다.
 */
@ConfigurationProperties("judozoo.kis")
data class KisProperties(
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    val appKey: String = "",
    val appSecret: String = "",
    val queryPermitsPerSecond: Int = 15,
)
