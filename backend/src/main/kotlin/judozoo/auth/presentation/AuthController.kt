package judozoo.auth.presentation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.auth.application.AuthService
import judozoo.auth.domain.CurrentUser
import judozoo.library.KST
import judozoo.library.web.ApiResponse
import judozoo.platform.google.GoogleOAuth
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDateTime
import java.util.Base64

/**
 * 로그인 — 구글 OAuth(OpenID Connect). `/api/auth/` 아래는 관문 허용목록이라 로그인이 필요한 곳(탈퇴)은 여기서 직접 건다.
 *
 * 로그인 시작에서 만든 `state`·`nonce`는 세션 쿠키에 담아 두고 콜백에서 대조한다(CSRF·토큰 재사용 방어) —
 * Python(authlib)이 쓰던 `_state_google_<state>` 자리 그대로다.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val google: GoogleOAuth,
    private val auth: AuthService,
    private val cookies: SessionCookies,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /** 프론트가 헤더 렌더와 로그인 유도 판단에 쓴다. 미로그인도 200으로 답한다. */
    data class Me(val authenticated: Boolean, val email: String? = null)

    @GetMapping("/login")
    fun login(request: HttpServletRequest, response: HttpServletResponse) {
        if (!google.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED")
        val (state, nonce) = token() to token()
        val expires = clock.instant().epochSecond + STATE_TTL_SECONDS
        cookies.write(response, session(request) + (STATE_PREFIX + state to mapOf("data" to mapOf("nonce" to nonce), "exp" to expires)))
        response.sendRedirect(google.authorizeUrl(state, nonce))
    }

    /** state 불일치·code 만료·네트워크 실패는 전부 첫 화면으로 돌려보낸다. */
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val session = session(request)
        val saved = state?.let { session[STATE_PREFIX + it] } as? Map<*, *>
        val nonce = (saved?.get("data") as? Map<*, *>)?.get("nonce") as? String
        val expires = (saved?.get("exp") as? Number)?.toLong() ?: 0
        val account = try {
            when {
                code == null || nonce == null -> null.also { log.warn("구글 콜백 처리 실패 — state가 세션에 없다") }
                clock.instant().epochSecond > expires -> null.also { log.warn("구글 콜백 처리 실패 — 로그인 시작이 만료됐다") }
                else -> google.exchange(code, nonce)
            }
        } catch (e: Exception) {
            log.warn("구글 콜백 처리 실패", e)
            null
        }
        val rest = session - (STATE_PREFIX + state)
        if (account == null) {
            cookies.write(response, rest)
            response.sendRedirect("/?login=failed")
            return
        }
        // 구글 계정이 아니라 내부 식별자로 된 사용자를 담는다 — 이후 요청의 로그가 이 값을 쓴다
        val user = auth.recordLogin(account, LocalDateTime.now(clock.withZone(KST)))
        cookies.write(response, rest + (CurrentUser.SESSION_KEY to user.toSession()))
        response.sendRedirect("/")
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse): ApiResponse<Nothing?> {
        cookies.write(response, session(request) - CurrentUser.SESSION_KEY)
        return ApiResponse.ok(null)
    }

    @PostMapping("/withdraw")
    fun withdraw(user: CurrentUser, request: HttpServletRequest, response: HttpServletResponse): ApiResponse<Nothing?> {
        auth.withdraw(user)
        cookies.write(response, session(request) - CurrentUser.SESSION_KEY)
        return ApiResponse.ok(null)
    }

    @GetMapping("/me")
    fun me(user: CurrentUser?): ApiResponse<Me> = ApiResponse.ok(user?.let { Me(true, it.email) } ?: Me(false))

    @Suppress("UNCHECKED_CAST")
    private fun session(request: HttpServletRequest): Map<String, Any?> =
        (request.getAttribute(LoginGateFilter.SESSION) as? Map<String, Any?>).orEmpty()

    private fun token(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private const val STATE_PREFIX = "_state_google_"

        /** 로그인을 시작하고 돌아오기까지 기다리는 시간 — authlib 기본값과 같다 */
        private const val STATE_TTL_SECONDS = 3600L
    }
}
