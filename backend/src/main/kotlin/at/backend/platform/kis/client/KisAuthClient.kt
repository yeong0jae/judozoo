package at.backend.platform.kis.client

import at.backend.platform.kis.client.request.KisApprovalRequest
import at.backend.platform.kis.client.request.KisTokenRequest
import at.backend.platform.kis.client.response.KisApprovalResponse
import at.backend.platform.kis.client.response.KisAuthResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

class KisAuthClient(
    private val appKey: String,
    private val appSecret: String,
    private val restClient: RestClient,
) {

    private val log = KotlinLogging.logger {}

    fun issueToken(): KisAuthResponse {
        log.info { "KIS 호출 → POST /oauth2/tokenP (액세스 토큰 발급)" }
        val response = restClient.post()
            .uri("/oauth2/tokenP")
            .contentType(MediaType.APPLICATION_JSON)
            .body(KisTokenRequest(appKey, appSecret))
            .retrieve()
            .body(KisAuthResponse::class.java)
            ?: error("KIS 토큰 응답이 비어있습니다")
        log.info { "KIS 응답 ← POST /oauth2/tokenP 액세스 토큰 발급 완료" }
        return response
    }

    fun issueApprovalKey(): KisApprovalResponse {
        log.info { "KIS 호출 → POST /oauth2/Approval (WS approval_key 발급)" }
        val response = restClient.post()
            .uri("/oauth2/Approval")
            .contentType(MediaType.APPLICATION_JSON)
            .body(KisApprovalRequest(appKey = appKey, secretKey = appSecret))
            .retrieve()
            .body(KisApprovalResponse::class.java)
            ?: error("KIS WebSocket approval_key 응답이 비어있습니다")
        log.info { "KIS 응답 ← POST /oauth2/Approval approval_key 발급 완료" }
        return response
    }
}
