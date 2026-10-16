package judozoo.auth.presentation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import judozoo.JudozooProperties
import judozoo.library.StarletteSession
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration

/**
 * 세션 쿠키 `judozoo_session` — Starlette와 같은 서명 쿠키라 Python이 발급한 쿠키가 그대로 이어진다.
 *
 * 서명만 되고 암호화되지 않는다 — 누구나 읽을 수 있으니 사람을 가리키는 값은 내부 `id`와 이메일뿐이다.
 * **바뀔 때만 내려보낸다**(로그인·로그아웃·옛 모양 고치기) — 요청마다 내려보내면 응답이 쓸데없이 커진다.
 * 그래서 수명(14일)은 마지막으로 내려보낸 때부터 센다. 운영 전제라 늘 `Secure`다(부하 분산기가 TLS 종단).
 * 빈은 [AuthWebConfig]가 만든다 — 웹 슬라이스 테스트에도 관문과 함께 실리게.
 */
class SessionCookies(properties: JudozooProperties, objectMapper: ObjectMapper, private val clock: Clock) {
    private val session = StarletteSession(properties.sessionSecret, objectMapper)

    /** 서명이 맞고 살아 있는 세션 내용. 없으면 null. */
    fun read(request: HttpServletRequest): Map<String, Any?>? {
        val cookie = request.cookies?.firstOrNull { it.name == NAME } ?: return null
        return session.read(cookie.value, clock.instant())
    }

    fun write(response: HttpServletResponse, data: Map<String, Any?>) {
        if (data.isEmpty()) return clear(response)
        response.addHeader("Set-Cookie", "$NAME=${session.write(data, clock.instant())}; path=/; Max-Age=${MAX_AGE.seconds}; httponly; samesite=lax; secure")
    }

    /** Starlette가 세션을 비울 때 내려보내는 것과 같은 모양. */
    fun clear(response: HttpServletResponse) {
        response.addHeader("Set-Cookie", "$NAME=null; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; httponly; samesite=lax; secure")
    }

    companion object {
        const val NAME = "judozoo_session"

        /** Starlette 기본값 */
        val MAX_AGE: Duration = Duration.ofDays(14)
    }
}
