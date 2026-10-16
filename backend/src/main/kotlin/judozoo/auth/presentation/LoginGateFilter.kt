package judozoo.auth.presentation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.library.JsonLogFormatter
import judozoo.library.web.ApiResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
class LoginGateFilter(
    private val cookies: SessionCookies,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val session = cookies.read(request) ?: Session()
        val user = session.user
        request.setAttribute(SESSION, session)
        request.setAttribute(CURRENT_USER, user)
        if (user != null && session.carriesStaleUser) cookies.write(response, session.withUser(user))
        val path = request.requestURI
        user?.let { MDC.put(JsonLogFormatter.MDC_USER, it.id.toString()) }
        MDC.put(JsonLogFormatter.MDC_REQUEST, "${request.method} $path")
        try {
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

    private fun logAccess(request: HttpServletRequest, status: Int) {
        val path = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        if (status < 400 && QUIET_PATHS.any { request.requestURI.startsWith(it) }) return
        access.info("{}:{} - \"{} {} {}\" {}", request.remoteAddr, request.remotePort, request.method, path, request.protocol, status)
    }

    companion object {
        private val access = org.slf4j.LoggerFactory.getLogger("judozoo.access")
        private val QUIET_PATHS = listOf("/actuator")

        const val COOKIE_NAME = SessionCookies.NAME
        const val CURRENT_USER = "judozoo.currentUser"
        const val SESSION = "judozoo.session"
    }
}
