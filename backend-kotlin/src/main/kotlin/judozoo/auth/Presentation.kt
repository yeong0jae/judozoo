package judozoo.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.JudozooProperties
import judozoo.library.ApiError
import judozoo.library.StarletteSession
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * `/api` 기본 차단. 허용목록(`Gate`)에 있는 경로만 연다.
 *
 * 필터로 두면 등록을 빠뜨려도 노출이 아니라 불편으로 끝난다. 읽은 사용자는 요청 속성 [CURRENT_USER]에 둔다.
 */
@Component
class LoginGateFilter(
    properties: JudozooProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : OncePerRequestFilter() {
    private val session = StarletteSession(properties.sessionSecret, objectMapper)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val user = currentUser(request)
        request.setAttribute(CURRENT_USER, user)

        // 정규화 전 경로로 본다 — `/api/market/kospi/../futures/...` 같은 꼬리로 허용목록을 타지 못하게
        val path = request.requestURI
        if (path.startsWith("/api") && !Gate.isPublic(path) && user == null) {
            response.status = 401
            response.contentType = "application/json"
            objectMapper.writeValue(response.outputStream, ApiError("UNAUTHORIZED", 401))
            return
        }
        chain.doFilter(request, response)
    }

    private fun currentUser(request: HttpServletRequest): CurrentUser? {
        val cookie = request.cookies?.firstOrNull { it.name == COOKIE_NAME } ?: return null
        val raw = session.read(cookie.value, clock.instant()) ?: return null
        return CurrentUser.fromSession(raw[CurrentUser.SESSION_KEY])
    }

    companion object {
        const val COOKIE_NAME = "judozoo_session"
        const val CURRENT_USER = "judozoo.currentUser"
    }
}
