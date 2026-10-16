package judozoo.auth.application

import judozoo.auth.domain.AppUser
import judozoo.auth.domain.AppUserRepository
import judozoo.auth.domain.CurrentUser
import judozoo.feedback.application.FeedbackService
import judozoo.library.JsonLogFormatter
import judozoo.platform.google.GoogleOAuth
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AuthService(private val users: AppUserRepository, private val feedback: FeedbackService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recordLogin(account: GoogleOAuth.Account, at: LocalDateTime): CurrentUser {
        val user = users.findByGoogleSub(account.sub)?.apply { loggedIn(account.email, at) }
            ?: users.save(AppUser(account.sub, account.email, at))
        users.flush()
        MDC.putCloseable(JsonLogFormatter.MDC_USER, user.id.toString()).use { log.info("로그인") }
        return user.toCurrentUser()
    }

    @Transactional
    fun withdraw(user: CurrentUser) {
        val row = users.findById(user.id).orElse(null) ?: return
        val erased = feedback.eraseBy(row.id!!)
        users.delete(row)
        log.info("탈퇴 — 의견 {}건 함께 삭제", erased)
    }
}
