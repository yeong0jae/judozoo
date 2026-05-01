package at.backend.platform.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kis")
data class KisProperties(
    val appKey: String,
    val appSecret: String,
    val accountNo: String,
    val accountProductCode: String,
    val baseUrl: String,
    val wsUrl: String,
    val rateLimitPerSecond: Int,
)
