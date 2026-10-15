package judozoo.auth.presentation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.JudozooProperties
import judozoo.auth.domain.CurrentUser
import judozoo.library.web.ApiResponse
import judozoo.library.JsonLogFormatter
import judozoo.library.StarletteSession
import org.slf4j.MDC
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
        // 로그 맥락도 여기서 심는다. 관문이 이미 사용자를 손에 쥔 자리가 여기다
        val path = request.requestURI
        user?.let { MDC.put(JsonLogFormatter.MDC_USER, it.id.toString()) }
        MDC.put(JsonLogFormatter.MDC_REQUEST, "${request.method} $path")
        try {
            // 정규화 전 경로로 본다 — `/api/market/kospi/../futures/...` 같은 꼬리로 허용목록을 타지 못하게
            if (path.startsWith("/api") && !Gate.isPublic(path) && user == null) {
                response.status = 401
                response.contentType = "application/json"
                objectMapper.writeValue(response.outputStream, ApiResponse.error("UNAUTHORIZED", 401))
                return
            }
            chain.doFilter(request, response)
        } finally {
            logAccess(request, response.status)
            MDC.remove(JsonLogFormatter.MDC_USER)
            MDC.remove(JsonLogFormatter.MDC_REQUEST)
        }
    }

    /**
     * uvicorn 접근 로그와 **같은 문장**을 남긴다 — "사용자 여정" 대시보드가 이 모양을 패턴으로 쪼갠다.
     * 헬스체크·메트릭은 10초마다 오므로 성공이면 남기지 않는다.
     */
    private fun logAccess(request: HttpServletRequest, status: Int) {
        val path = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        if (status < 400 && QUIET_PATHS.any { request.requestURI.startsWith(it) }) return
        access.info("{}:{} - \"{} {} {}\" {}", request.remoteAddr, request.remotePort, request.method, path, request.protocol, status)
    }

    private fun currentUser(request: HttpServletRequest): CurrentUser? {
        val cookie = request.cookies?.firstOrNull { it.name == COOKIE_NAME } ?: return null
        val raw = session.read(cookie.value, clock.instant()) ?: return null
        return CurrentUser.fromSession(raw[CurrentUser.SESSION_KEY])
    }

    companion object {
        private val access = org.slf4j.LoggerFactory.getLogger("judozoo.access")
        private val QUIET_PATHS = listOf("/health", "/actuator")

        const val COOKIE_NAME = "judozoo_session"
        const val CURRENT_USER = "judozoo.currentUser"
    }
}
