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

/** 가입자 기록 — 로그인·탈퇴. */
@Service
class AuthService(private val users: AppUserRepository, private val feedback: FeedbackService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 최초면 생성, 재방문이면 마지막 로그인 시각만 갱신. **내부 식별자로 사용자를 돌려준다** —
     * 세션에 넣어 두면 이후 요청이 DB 없이 로그에 사용자를 싣는다.
     */
    @Transactional
    fun recordLogin(account: GoogleOAuth.Account, at: LocalDateTime): CurrentUser {
        val user = users.findByGoogleSub(account.sub)?.apply { loggedIn(account.email, at) }
            ?: users.save(AppUser(account.sub, account.email, at))
        users.flush()
        // 콜백 중엔 아직 세션이 없어 관문이 사용자를 못 심으므로 여기서 직접 싣는다
        MDC.putCloseable(JsonLogFormatter.MDC_USER, user.id.toString()).use { log.info("로그인") }
        return user.toCurrentUser()
    }

    /**
     * 탈퇴 — 가입자 행과 그 사람이 보낸 의견을 한 트랜잭션으로 지운다. 이미 없으면(두 번 누름) 아무것도 안 한다.
     * Grafana 탈퇴 알림이 이 로그 한 줄을 센다 — 문구를 바꾸면 알림이 조용히 끊긴다.
     */
    @Transactional
    fun withdraw(user: CurrentUser) {
        val row = users.findById(user.id).orElse(null) ?: return
        val erased = feedback.eraseBy(row.id!!)
        users.delete(row)
        log.info("탈퇴 — 의견 {}건 함께 삭제", erased)
    }
}
