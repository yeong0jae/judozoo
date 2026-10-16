package judozoo.platform.vertex

import com.google.auth.oauth2.GoogleCredentials
import org.springframework.stereotype.Component

@Component
class AdcToken : VertexToken {
    private var credentials: GoogleCredentials? = null

    @Synchronized
    override fun get(refresh: Boolean): String {
        val c = credentials ?: GoogleCredentials.getApplicationDefault().createScoped(SCOPE).also { credentials = it }
        if (refresh) c.refresh() else c.refreshIfExpired()
        return checkNotNull(c.accessToken) { "ADC 토큰을 받지 못했다" }.tokenValue
    }

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    }
}
