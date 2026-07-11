package at.backend.platform.toss.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 토스증권 Open API 접속 정보. OAuth2 Client Credentials(client-id/secret)로 토큰 발급. */
@ConfigurationProperties(prefix = "toss.api")
data class TossApiProperties(
    val baseUrl: String = "https://openapi.tossinvest.com",
    val clientId: String = "",
    val clientSecret: String = "",
)
