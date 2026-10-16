package judozoo.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 가입자 기록. 키는 **이 테이블이 발급하는 [id]** 다. 구글 `sub`은 모든 앱에 같은 영구 계정 식별자라
 * 로그처럼 퍼지는 자리에 담으면 되돌릴 수 없다. [googleSub]은 UNIQUE로 중복 가입을 막고, 이메일은 바뀔 수 있어 키가 아니다.
 */
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

    /** 재방문 — 계정 이메일이 바뀌었을 수 있다. */
    fun loggedIn(email: String, at: LocalDateTime) {
        this.email = email
        lastLoginAt = at
    }

    fun toCurrentUser() = CurrentUser(id!!, email)
}
