package judozoo.library.brokertoken

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 브로커별 최신 토큰 한 줄. `provider`로 찾는다(UNIQUE).
 *
 * **토큰은 희소 자원이다.** 키움은 앱키당 하루 1개(재발급이 쌓이면 `start.html` 302로 거부), KIS는 앱키당 1분 1회,
 * 토스는 client당 1개만 유효하다. 메모리에만 두면 **재기동마다 발급을 소비**한다 — 2026-09-12 토요일 재기동 때
 * 키움이 신규 발급을 거부해 국내 화면 전체가 죽었다. 그래서 DB에 남긴다.
 *
 * `expires_at`은 **naive UTC**다 — 다른 테이블은 KST 벽시계를 쓰지만 이 값은 사람이 읽을 일이 없다.
 */
@Entity
@Table(name = "broker_token")
class BrokerToken(
    val provider: String,
    accessToken: String,
    expiresAt: LocalDateTime,
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // 토스 토큰은 JWT라 750자를 넘는다 — varchar(512)로 뒀다가 저장이 통째로 실패했다
    @Column(name = "access_token", columnDefinition = "text")
    var accessToken: String = accessToken
        protected set

    @Column(name = "expires_at")
    var expiresAt: LocalDateTime = expiresAt
        protected set

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = createdAt
        protected set

    val expiresAtInstant: Instant get() = expiresAt.toInstant(ZoneOffset.UTC)

    fun isAliveAt(now: Instant): Boolean = now.isBefore(expiresAtInstant)

    fun renew(token: String, expires: Instant, now: Instant) {
        accessToken = token
        expiresAt = LocalDateTime.ofInstant(expires, ZoneOffset.UTC)
        updatedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
    }

    companion object {
        fun issued(provider: String, token: String, expires: Instant, now: Instant) =
            BrokerToken(provider, token, LocalDateTime.ofInstant(expires, ZoneOffset.UTC), LocalDateTime.ofInstant(now, ZoneOffset.UTC))
    }
}
