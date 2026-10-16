package judozoo.platform.vertex

import com.google.auth.oauth2.GoogleCredentials
import org.springframework.stereotype.Component

/**
 * ADC 토큰. 운영은 VM 서비스 계정, 로컬은 `gcloud auth application-default login`.
 *
 * **처음 부를 때 읽는다** — 기동 때 읽으면 ADC가 없는 로컬·테스트에서 앱이 뜨지 않는다.
 * 만료 전까지 들고 있다가 필요할 때만 새로 받는다.
 */
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
