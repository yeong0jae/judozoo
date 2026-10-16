package judozoo.auth.presentation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.auth.domain.CurrentUser
import judozoo.library.JsonLogFormatter
import judozoo.library.web.ApiResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * `/api` 기본 차단. 허용목록(`Gate`)에 있는 경로만 연다.
 *
 * 필터로 두면 등록을 빠뜨려도 노출이 아니라 불편으로 끝난다. 읽은 사용자는 요청 속성 [CURRENT_USER]에,
 * 세션 내용 전체는 [SESSION]에 둔다(로그인 흐름이 OAuth state를 거기서 꺼낸다).
 */
@Component
class LoginGateFilter(
    private val cookies: SessionCookies,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val raw = cookies.read(request).orEmpty()
        val user = CurrentUser.fromSession(raw[CurrentUser.SESSION_KEY])
        request.setAttribute(SESSION, raw)
        request.setAttribute(CURRENT_USER, user)
        // 구글 `sub`이 실린 옛 쿠키를 지금 모양으로 다시 쓴다. 바뀔 때만 내려보내므로, 두면 그 사람이 다시 로그인할 때까지 `sub`이 실려 다닌다
        if (user != null && (raw[CurrentUser.SESSION_KEY] as Map<*, *>).keys != user.toSession().keys) {
            cookies.write(response, raw + (CurrentUser.SESSION_KEY to user.toSession()))
        }
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

    companion object {
        private val access = org.slf4j.LoggerFactory.getLogger("judozoo.access")
        private val QUIET_PATHS = listOf("/health", "/actuator")

        const val COOKIE_NAME = SessionCookies.NAME
        const val CURRENT_USER = "judozoo.currentUser"
        const val SESSION = "judozoo.session"
    }
}
