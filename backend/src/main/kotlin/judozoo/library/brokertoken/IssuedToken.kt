package judozoo.library.brokertoken

import judozoo.library.BrokerTokenUnavailable
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 브로커 액세스 토큰을 발급해 `broker_token`에 남기고 빌려 준다. 브로커 클라이언트 하나에 하나씩 둔다.
 *
 * - 살아 있는 동안은 메모리에서 답한다. 재기동 직후엔 **DB를 먼저 본다** — 발급은 희소하다([BrokerToken])
 * - DB에도 살아 있는 토큰이 없을 때만 [issue]로 발급한다. 동시 요청은 한 번만 발급한다
 * - 발급이 실패하면 [BACKOFF] 동안 다시 두드리지 않는다([BrokerTokenUnavailable]). 없으면 실패할 때마다 매 호출이
 *   토큰을 다시 요청한다 — 실측 11분에 185회를 두드렸다. 차단이라면 풀릴 기회도 없어진다
 * - 브로커가 "토큰 무효"로 답하면 [invalidate] — DB에서도 지운다. 남아 있으면 재기동 때 죽은 토큰을 되살린다
 * - DB가 안 되면 경고만 남기고 메모리로 동작한다
 */
class IssuedToken(
    private val provider: String,
    private val repository: BrokerTokenRepository,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
    private val issue: () -> Issued,
) : TokenSource {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 발급 응답 — 토큰과 우리가 정한 만료 시각(브로커가 말한 만료보다 여유를 둔다). */
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
        /** 발급이 거부된 뒤 다시 시도하기까지 기다리는 시간. KIS는 같은 앱키로 1분 안에 재발급하면 거부한다 */
        val BACKOFF: Duration = Duration.ofSeconds(60)
    }
}
