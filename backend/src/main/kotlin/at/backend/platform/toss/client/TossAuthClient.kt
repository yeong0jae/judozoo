package at.backend.platform.toss.client

import at.backend.platform.toss.config.TossApiProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 토스 OAuth2 액세스 토큰 발급/캐시. Client Credentials Grant(client_id/secret → access_token).
 * 토큰 유효기간(expires_in, 보통 24h) 안에서 재사용하고, 만료 임박 시 재발급한다. refresh 없음.
 * client당 유효 토큰은 1개라 재발급 시 이전 토큰은 무효화된다(단일 인스턴스 기준 문제 없음).
 */
@Component
class TossAuthClient(
    private val tossRestClient: RestClient,
    private val properties: TossApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Instant = Instant.MIN

    fun invalidate() {
        lock.withLock {
            accessToken = null
            tokenExpiresAt = Instant.MIN
            log.warn("Toss 토큰 캐시 무효화")
        }
    }

    fun getAccessToken(): String {
        val token = accessToken
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) return token

        return lock.withLock {
            val current = accessToken
            if (current != null && Instant.now().isBefore(tokenExpiresAt)) return@withLock current

            log.info("Requesting new access token from Toss API")
            val response = requestNewToken()
            accessToken = response.access_token
            // 만료 5분 전에 갱신되도록 마진
            tokenExpiresAt = Instant.now().plusSeconds((response.expires_in - 300).coerceAtLeast(60))
            response.access_token
        }
    }

    private fun requestNewToken(): TokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
        }
        return tossRestClient.post()
            .uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse::class.java)
            ?: throw IllegalStateException("Toss 토큰 응답이 비어있음")
    }

    /** OAuth2 표준 토큰 응답(공통 envelope 아님). */
    data class TokenResponse(
        val access_token: String = "",
        val token_type: String = "Bearer",
        val expires_in: Long = 86400,
    )
}
