package at.backend.platform.kis.client

import at.backend.platform.kis.config.KisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class KisAuthClient(
    private val kisRestClient: RestClient,
    private val properties: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Instant = Instant.MIN

    fun getAccessToken(): String {
        val token = accessToken
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) return token

        return lock.withLock {
            val current = accessToken
            if (current != null && Instant.now().isBefore(tokenExpiresAt)) return@withLock current

            log.info("KIS access token 발급 요청")
            val response = kisRestClient.post()
                .uri("/oauth2/tokenP")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "appkey" to properties.appKey,
                        "appsecret" to properties.appSecret,
                    ),
                )
                .retrieve()
                .body(TokenResponse::class.java)
                ?: throw IllegalStateException("KIS token 응답이 null")

            val newToken = response.access_token
                ?: throw IllegalStateException("KIS token 응답에 access_token 없음")

            accessToken = newToken
            tokenExpiresAt = Instant.now().plusSeconds(23 * 3600)
            log.info("KIS access token 발급 완료")
            newToken
        }
    }

    data class TokenResponse(
        val access_token: String?,
        val token_type: String?,
        val expires_in: Long?,
        val access_token_token_expired: String?,
    )
}
