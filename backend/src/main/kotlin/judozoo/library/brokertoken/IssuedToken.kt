package judozoo.library.brokertoken

import judozoo.library.BrokerTokenUnavailable
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

class IssuedToken(
    private val provider: String,
    private val repository: BrokerTokenRepository,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
    private val issue: () -> Issued,
) : TokenSource {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Issued(val token: String, val expiresAt: Instant)

    @Volatile private var current: Issued? = null
    @Volatile private var retryAfter: Instant = Instant.MIN

    override fun get(): String {
        current?.takeIf { clock.instant().isBefore(it.expiresAt) }?.let { return it.token }
        synchronized(this) {
            val now = clock.instant()
            current?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.token }
            restore(now)?.let {
                current = it
                return it.token
            }
            if (now.isBefore(retryAfter)) {
                throw BrokerTokenUnavailable("$provider 토큰 발급 백오프 중 — ${Duration.between(now, retryAfter).seconds}초 후 재시도")
            }
            log.info("{} access token 발급 요청", provider)
            val issued = try {
                issue()
            } catch (e: Exception) {
                retryAfter = now.plus(BACKOFF)
                log.error("{} 토큰 발급 실패 — {}초 백오프", provider, BACKOFF.seconds, e)
                throw e
            }
            current = issued
            save(issued, now)
            log.info("{} access token 발급 완료 — 만료 {}", provider, issued.expiresAt)
            return issued.token
        }
    }

    override fun invalidate() {
        synchronized(this) {
            current = null
            try {
                transaction.executeWithoutResult { repository.deleteByProvider(provider) }
            } catch (e: Exception) {
                log.warn("{} 토큰 삭제 실패", provider, e)
            }
        }
        log.warn("{} 토큰 캐시 무효화", provider)
    }

    private fun restore(now: Instant): Issued? = try {
        repository.findByProvider(provider)?.takeIf { it.isAliveAt(now) }?.let {
            log.info("{} 토큰을 DB에서 복원 — 만료 {}", provider, it.expiresAtInstant)
            Issued(it.accessToken, it.expiresAtInstant)
        }
    } catch (e: Exception) {
        log.warn("{} 토큰 복원 실패 — 새로 발급한다", provider, e)
        null
    }

    private fun save(issued: Issued, now: Instant) {
        try {
            transaction.executeWithoutResult {
                val row = repository.findByProvider(provider)?.apply { renew(issued.token, issued.expiresAt, now) }
                    ?: BrokerToken.issued(provider, issued.token, issued.expiresAt, now)
                repository.save(row)
            }
        } catch (e: Exception) {
            log.warn("{} 토큰 저장 실패 — 메모리 캐시로만 동작한다", provider, e)
        }
    }

    companion object {
        val BACKOFF: Duration = Duration.ofSeconds(60)
    }
}
