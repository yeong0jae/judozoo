package judozoo.platform.google

import org.springframework.boot.context.properties.ConfigurationProperties

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
