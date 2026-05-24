package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KIS 액세스 토큰 캐시. 24h 유효 — 1h 마진을 두고 23h만 캐시한 뒤 자동 재발급.
 * `by lazy` 영구 캐시는 24h 후 만료되어도 갱신되지 않는 버그라 만료 검사로 교체.
 */
@Component
class KisAccessTokenProvider(private val authClient: KisAuthClient) {
    private val log = KotlinLogging.logger {}
    private val lock = ReentrantLock()

    @Volatile private var cached: String? = null
    @Volatile private var expiresAt: Instant = Instant.MIN

    val token: String
        get() {
            val now = Instant.now()
            cached?.takeIf { now.isBefore(expiresAt) }?.let { return it }
            return lock.withLock {
                cached?.takeIf { Instant.now().isBefore(expiresAt) }?.let { return@withLock it }
                log.info { "KIS 액세스 토큰 신규 발급 (만료/미존재)" }
                val response = authClient.issueToken()
                cached = response.accessToken
                expiresAt = Instant.now().plusSeconds(TOKEN_TTL_SECONDS)
                response.accessToken
            }
        }

    companion object {
        // KIS 토큰 24h 유효 — 1h 마진
        private const val TOKEN_TTL_SECONDS: Long = 23 * 3600
    }
}
