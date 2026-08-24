package at.backend.platform.kiwoom.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 키움 OpenAPI 자격증명/엔드포인트.
 * 주도주 후보 발굴(시세·테마·프로그램매매) 조회에 사용한다.
 */
@ConfigurationProperties(prefix = "kiwoom.api")
data class KiwoomApiProperties(
    val baseUrl: String = "https://api.kiwoom.com",
    val appKey: String = "",
    val appSecret: String = "",
)
