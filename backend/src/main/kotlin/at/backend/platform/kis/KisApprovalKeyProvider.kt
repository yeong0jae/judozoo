package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KIS WebSocket approval_key 캐시. 유효기간이 토큰과 동일(24h)이라 가정하고 23h 마진.
 * `by lazy` 영구 캐시 → 만료 검사로 교체.
 */
@Component
class KisApprovalKeyProvider(private val authClient: KisAuthClient) {
    private val log = KotlinLogging.logger {}
    private val lock = ReentrantLock()

    @Volatile private var cached: String? = null
    @Volatile private var expiresAt: Instant = Instant.MIN

    val approvalKey: String
        get() {
            val now = Instant.now()
            cached?.takeIf { now.isBefore(expiresAt) }?.let { return it }
            return lock.withLock {
                cached?.takeIf { Instant.now().isBefore(expiresAt) }?.let { return@withLock it }
                log.info { "KIS WS approval_key 신규 발급 (만료/미존재)" }
                val response = authClient.issueApprovalKey()
                cached = response.approvalKey
                expiresAt = Instant.now().plusSeconds(KEY_TTL_SECONDS)
                response.approvalKey
            }
        }

    companion object {
        // 토큰과 동일 24h 가정 + 1h 마진
        private const val KEY_TTL_SECONDS: Long = 23 * 3600
    }
}
