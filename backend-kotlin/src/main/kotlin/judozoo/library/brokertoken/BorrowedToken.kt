package judozoo.library.brokertoken

import judozoo.library.BrokerTokenUnavailable
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Python이 발급해 `broker_token`에 남긴 토큰을 빌려 쓴다. 브로커 클라이언트 하나에 하나씩 둔다.
 *
 * - 살아 있는 동안은 메모리에서 답한다. 만료되면 DB를 다시 읽는다 — Python이 새로 발급해 두었을 수 있다
 * - **발급하지 않는다.** DB에 살아 있는 토큰이 없으면 [BrokerTokenUnavailable]. Python 폴러가 수 초 안에 다시 발급한다
 * - 브로커가 "토큰 무효"로 답하면 [invalidate]. 같은 토큰을 DB에서 다시 읽어 와도 쓰지 않는다 —
 *   죽은 토큰으로 브로커를 계속 두드리지 않게, Python이 새 토큰을 남길 때까지 기다린다
 */
class BorrowedToken(
    private val provider: String,
    private val repository: BrokerTokenRepository,
    private val clock: Clock,
) : TokenSource {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var current: BrokerToken? = null
    @Volatile private var dead: String? = null

    override fun get(): String {
        val now = clock.instant()
        current?.takeIf { it.isAliveAt(now) }?.let { return it.accessToken }

        synchronized(this) {
            current?.takeIf { it.isAliveAt(now) }?.let { return it.accessToken }

            val stored = repository.findByProvider(provider)
            if (stored == null || !stored.isAliveAt(now) || stored.accessToken == dead) {
                current = null
                throw BrokerTokenUnavailable("$provider 토큰 없음 — Python이 발급하기를 기다린다")
            }
            log.info("{} 토큰을 DB에서 읽음 — 만료 {}", provider, stored.expiresAtInstant)
            current = stored
            return stored.accessToken
        }
    }

    /** 브로커가 이 토큰을 거부했다. DB는 건드리지 않는다 — 지우고 새로 받는 건 Python 몫이다. */
    override fun invalidate() {
        synchronized(this) {
            dead = current?.accessToken ?: dead
            current = null
        }
        log.warn("{} 토큰 캐시 무효화", provider)
    }
}
