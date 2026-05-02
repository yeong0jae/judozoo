package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import org.springframework.stereotype.Component

@Component
class KisAccessTokenProvider(authClient: KisAuthClient) {

    private val token: String = authClient.issueToken().accessToken

    fun getToken(): String = token
}
