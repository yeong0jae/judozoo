package at.backend.platform.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kis.api")
data class KisApiProperties(
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    val appKey: String = "",
    val appSecret: String = "",
)
