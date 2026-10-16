package judozoo.platform.google

import org.springframework.boot.context.properties.ConfigurationProperties

/** `judozoo.google.*` — 구글 OAuth. 값이 비면 로그인 라우트가 503을 준다 — 로컬에서 굳이 안 채워도 나머지는 돈다. */
@ConfigurationProperties("judozoo.google")
data class GoogleProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val redirectUri: String = "https://judozoo.com/api/auth/callback",
    val authorizeUrl: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val tokenUrl: String = "https://oauth2.googleapis.com/token",
) {
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}
