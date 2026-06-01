package at.backend.platform.kiwoom.client

import at.backend.platform.kiwoom.config.KiwoomApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class KiwoomAuthClient(
    private val kiwoomRestClient: RestClient,
    private val properties: KiwoomApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Instant = Instant.MIN

    /** API 응답에서 토큰 무효(8005)가 확인됐을 때 호출. 다음 [getAccessToken]이 새 토큰을 발급한다. */
    fun invalidate() {
        lock.withLock {
            accessToken = null
            tokenExpiresAt = Instant.MIN
            log.warn("Kiwoom 토큰 캐시 무효화")
        }
    }

    fun getAccessToken(): String {
        val token = accessToken
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token
        }

        return lock.withLock {
            val currentToken = accessToken
            if (currentToken != null && Instant.now().isBefore(tokenExpiresAt)) {
                return@withLock currentToken
            }

            log.info("Requesting new access token from Kiwoom API")
            val newToken = requestNewToken()
            accessToken = newToken
            tokenExpiresAt = Instant.now().plusSeconds(23 * 3600) // 23h (1h margin under 24h validity)
            newToken
        }
    }

    private fun requestNewToken(): String {
        try {
            val response = kiwoomRestClient.post()
                .uri("/oauth2/token")
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "appkey" to properties.appKey,
                        "secretkey" to properties.appSecret,
                    ),
                )
                .retrieve()
                .body(TokenResponse::class.java)
                ?: throw IllegalStateException("Token response is null")

            if (response.return_code != null && response.return_code != 0) {
                log.error(
                    "Kiwoom API authentication failed. Code: {}, Message: {}",
                    response.return_code,
                    response.return_msg,
                )
                throw IllegalStateException("Kiwoom API error: ${response.return_msg}")
            }

            log.info(
                "Successfully obtained access token, expires at: {}, token_type: {}",
                response.expires_dt ?: "N/A",
                response.token_type ?: "N/A",
            )

            return response.token ?: response.access_token
                ?: throw IllegalStateException("No token found in response")
        } catch (e: Exception) {
            log.error("Failed to obtain access token. Please verify your API credentials.", e)
            throw RuntimeException("Failed to authenticate with Kiwoom API.", e)
        }
    }

    data class TokenResponse(
        val expires_dt: String?,
        val token_type: String?,
        val token: String?,
        val access_token: String?,
        val return_code: Int?,
        val return_msg: String?,
    )
}
