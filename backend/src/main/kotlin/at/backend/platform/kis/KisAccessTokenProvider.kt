package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import org.springframework.stereotype.Component

@Component
class KisAccessTokenProvider(private val authClient: KisAuthClient) {

    val token: String by lazy { authClient.issueToken().accessToken }
}
