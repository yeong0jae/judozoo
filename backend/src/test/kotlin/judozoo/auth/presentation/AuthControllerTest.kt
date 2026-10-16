package judozoo.auth.presentation

import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import jakarta.servlet.http.Cookie
import judozoo.JudozooProperties
import judozoo.LoggedIn
import judozoo.auth.application.AuthService
import judozoo.auth.domain.CurrentUser
import judozoo.platform.google.GoogleOAuth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@WebMvcTest(AuthController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class AuthControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var google: GoogleOAuth

    @MockkBean
    private lateinit var auth: AuthService

    private val cookies = SessionCookies(
        JudozooProperties(sessionSecret = "test-secret"), JsonMapper.builder().build(), Clock.fixed(Instant.ofEpochSecond(1_792_070_321), ZoneOffset.UTC),
    )

    private fun sessionIn(setCookie: String): Map<String, Any?>? {
        val request = org.springframework.mock.web.MockHttpServletRequest()
        request.setCookies(Cookie(SessionCookies.NAME, setCookie.substringAfter("=").substringBefore(";")))
        return cookies.read(request)
    }

    @Nested
    inner class 로그인_상태 {
        @Test
        fun `미로그인이면 인증되지 않았다고 답한다`() {
            mvc.get("/api/auth/me").andExpect {
                status { isOk() }
                content { json("""{"code":"SUCCESS","status":200,"data":{"authenticated":false,"email":null}}""") }
            }
        }

        @Test
        fun `로그인하면 이메일을 돌려준다`() {
            mvc.get("/api/auth/me") { cookie(LoggedIn.cookie) }.andExpect { content { json("""{"data":{"authenticated":true,"email":"a@example.com"}}""") } }
        }
    }

    @Nested
    inner class 로그인_흐름 {
        @Test
        fun `구글 설정이 없으면 503`() {
            every { google.configured } returns false

            mvc.get("/api/auth/login").andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.code") { value("OAUTH_NOT_CONFIGURED") }
            }
        }

        @Test
        fun `구글로 보냈다가 돌아오면 내부 식별자로 로그인한다`() {
            every { google.configured } returns true
            every { google.authorizeUrl(any(), any()) } answers { "https://accounts.google.com/auth?state=${firstArg<String>()}" }
            val started = mvc.get("/api/auth/login").andExpect { status { is3xxRedirection() } }.andReturn().response
            val state = started.redirectedUrl!!.substringAfter("state=")
            val session = sessionIn(started.getHeader("Set-Cookie")!!)!!
            val nonce = ((session["_state_google_$state"] as Map<*, *>)["data"] as Map<*, *>)["nonce"] as String
            every { google.exchange("code-1", nonce) } returns GoogleOAuth.Account("g-123", "a@example.com")
            every { auth.recordLogin(any(), any()) } returns CurrentUser(7, "a@example.com")

            val back = mvc.get("/api/auth/callback") {
                param("code", "code-1"); param("state", state)
                cookie(Cookie(SessionCookies.NAME, started.getHeader("Set-Cookie")!!.substringAfter("=").substringBefore(";")))
            }.andReturn().response

            back.redirectedUrl shouldBe "/"
            val loggedIn = sessionIn(back.getHeader("Set-Cookie")!!)!!
            loggedIn["user"] shouldBe mapOf("id" to 7, "email" to "a@example.com")
            loggedIn.keys shouldBe setOf("user")
        }

        @Test
        fun `세션에 없는 state로 돌아오면 첫 화면으로 돌려보낸다`() {
            mvc.get("/api/auth/callback") { param("code", "code-1"); param("state", "forged") }.andExpect { redirectedUrl("/?login=failed") }

            verify(exactly = 0) { google.exchange(any(), any()) }
        }

        @Test
        fun `구글 처리가 실패하면 첫 화면으로 돌려보낸다`() {
            every { google.configured } returns true
            every { google.authorizeUrl(any(), any()) } answers { "https://accounts.google.com/auth?state=${firstArg<String>()}" }
            val started = mvc.get("/api/auth/login").andReturn().response
            every { google.exchange(any(), any()) } throws IllegalStateException("invalid_grant")

            mvc.get("/api/auth/callback") {
                param("code", "code-1"); param("state", started.redirectedUrl!!.substringAfter("state="))
                cookie(Cookie(SessionCookies.NAME, started.getHeader("Set-Cookie")!!.substringAfter("=").substringBefore(";")))
            }.andExpect { redirectedUrl("/?login=failed") }
        }
    }

    @Nested
    inner class 로그아웃과_탈퇴 {
        @Test
        fun `로그아웃하면 사용자를 세션에서 뺀다`() {
            val response = mvc.post("/api/auth/logout") { cookie(LoggedIn.cookie) }.andReturn().response

            sessionIn(response.getHeader("Set-Cookie")!!)!!.containsKey("user") shouldBe false
        }

        @Test
        fun `로그인하지 않으면 탈퇴할 수 없다`() {
            mvc.post("/api/auth/withdraw").andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
        }

        @Test
        fun `탈퇴하면 가입자를 지우고 사용자를 세션에서 뺀다`() {
            every { auth.withdraw(CurrentUser(7, "a@example.com")) } just runs

            val response = mvc.post("/api/auth/withdraw") { cookie(LoggedIn.cookie) }.andExpect { jsonPath("$.status") { value(200) } }.andReturn().response

            verify { auth.withdraw(CurrentUser(7, "a@example.com")) }
            response.getHeader("Set-Cookie")!!.lowercase() shouldContain "secure"
            sessionIn(response.getHeader("Set-Cookie")!!)!!.containsKey("user") shouldBe false
        }

        @Test
        fun `세션이 비면 쿠키를 지운다`() {
            val r = org.springframework.mock.web.MockHttpServletResponse()
            cookies.write(r, mapOf("user" to mapOf("id" to 7, "email" to "a@example.com")))
            val value = r.getHeader("Set-Cookie")!!.substringAfter("=").substringBefore(";")

            val response = mvc.post("/api/auth/logout") { cookie(Cookie(SessionCookies.NAME, value)) }.andReturn().response

            response.getHeader("Set-Cookie")!! shouldStartWith "judozoo_session=null;"
        }
    }
}
