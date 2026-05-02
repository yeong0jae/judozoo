package at.backend.platform.kis.client

import at.backend.platform.kis.client.request.KisTokenRequest
import at.backend.platform.kis.client.response.KisAuthResponse
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

class KisAuthClient(
    private val appKey: String,
    private val appSecret: String,
    private val restClient: RestClient,
) {

    fun issueToken(): KisAuthResponse {
        return restClient.post()
            .uri("/oauth2/tokenP")
            .contentType(MediaType.APPLICATION_JSON)
            .body(KisTokenRequest(appKey, appSecret))
            .retrieve()
            .body(KisAuthResponse::class.java)
            ?: error("KIS 토큰 응답이 비어있습니다")
    }
}
