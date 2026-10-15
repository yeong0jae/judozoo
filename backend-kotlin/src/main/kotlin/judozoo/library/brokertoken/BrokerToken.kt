package judozoo.library.brokertoken

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 브로커별 최신 토큰 한 줄. `provider`로 찾는다(UNIQUE).
 *
 * **토큰은 희소 자원이다.** 키움은 앱키당 하루 1개, KIS는 앱키당 1분 1회, 토스는 client당 1개만 유효하다.
 * 공존하는 동안(028) **발급·저장·삭제는 Python만 한다.** Kotlin은 이 줄을 읽기만 한다.
 *
 * `expires_at`은 **naive UTC**다 — 다른 테이블은 KST 벽시계를 쓰지만 이 값은 Python 인증 모듈이 UTC로 계산한다.
 */
@Entity
@Table(name = "broker_token")
class BrokerToken(
    @Id
    val id: Long,
    val provider: String,
    // 토스 토큰은 JWT라 750자를 넘는다
    @Column(name = "access_token", columnDefinition = "text")
    val accessToken: String,
    @Column(name = "expires_at")
    val expiresAt: LocalDateTime,
    @Column(name = "created_at")
    val createdAt: LocalDateTime,
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime,
) {
    val expiresAtInstant: Instant get() = expiresAt.toInstant(ZoneOffset.UTC)

    fun isAliveAt(now: Instant): Boolean = now.isBefore(expiresAtInstant)
}
