package judozoo

import org.springframework.boot.context.properties.ConfigurationProperties

/** `judozoo.*` 설정. */
@ConfigurationProperties("judozoo")
data class JudozooProperties(
    /** 세션 쿠키 서명 키(`SESSION_SECRET`). */
    val sessionSecret: String,
)
