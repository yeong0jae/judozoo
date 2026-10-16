package judozoo.auth.presentation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.JudozooProperties
import judozoo.library.StarletteSession
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration

class SessionCookies(properties: JudozooProperties, objectMapper: ObjectMapper, private val clock: Clock) {
    private val session = StarletteSession(properties.sessionSecret, objectMapper)

    fun read(request: HttpServletRequest): Map<String, Any?>? {
        val cookie = request.cookies?.firstOrNull { it.name == NAME } ?: return null
        return session.read(cookie.value, clock.instant())
    }

    fun write(response: HttpServletResponse, data: Map<String, Any?>) {
        if (data.isEmpty()) return clear(response)
        response.addHeader("Set-Cookie", "$NAME=${session.write(data, clock.instant())}; path=/; Max-Age=${MAX_AGE.seconds}; httponly; samesite=lax; secure")
    }

    fun clear(response: HttpServletResponse) {
        response.addHeader("Set-Cookie", "$NAME=null; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; httponly; samesite=lax; secure")
    }

    companion object {
        const val NAME = "judozoo_session"

        val MAX_AGE: Duration = Duration.ofDays(14)
    }
}
