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

    data class Me(val authenticated: Boolean, val email: String? = null)

    @GetMapping("/login")
    fun login(request: HttpServletRequest, response: HttpServletResponse) {
        if (!google.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED")
        val (state, nonce) = token() to token()
        val expires = clock.instant().epochSecond + STATE_TTL_SECONDS
        cookies.write(response, session(request) + (STATE_PREFIX + state to mapOf("data" to mapOf("nonce" to nonce), "exp" to expires)))
        response.sendRedirect(google.authorizeUrl(state, nonce))
    }

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

        private const val STATE_TTL_SECONDS = 3600L
    }
}
