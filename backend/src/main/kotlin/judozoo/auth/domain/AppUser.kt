package judozoo.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "app_user")
class AppUser(
    @Column(name = "google_sub") val googleSub: String,
    email: String,
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    var email: String = email
        protected set

    @Column(name = "last_login_at")
    var lastLoginAt: LocalDateTime = createdAt
        protected set

    fun loggedIn(email: String, at: LocalDateTime) {
        this.email = email
        lastLoginAt = at
    }

    fun toCurrentUser() = CurrentUser(id!!, email)
}
