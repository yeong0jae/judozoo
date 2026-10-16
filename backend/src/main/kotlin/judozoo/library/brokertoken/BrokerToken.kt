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
