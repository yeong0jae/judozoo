package judozoo.auth.presentation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import judozoo.JudozooProperties
import judozoo.auth.domain.CurrentUser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LoginGateFilterTest {
    /** Python이 secret `test-secret`으로 발급한 쿠키 — 사용자 id 7. */
    private val pythonCookie =
        "eyJ1c2VyIjogeyJpZCI6IDcsICJlbWFpbCI6ICJhQGV4YW1wbGUuY29tIn0sICJfc3RhdGVfZ29vZ2xlX3giOiB7ImRhdGEiOiAxfX0=" +
            ".atDSsQ._chmEutnIautJuu1n_0keYIpTb0"
    private val filter = LoginGateFilter(
        JudozooProperties(sessionSecret = "test-secret"),
        JsonMapper.builder().build(),
        Clock.fixed(Instant.ofEpochSecond(1_792_070_321), ZoneOffset.UTC),
    )

    private fun call(path: String, cookie: String? = null): Pair<MockHttpServletRequest, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", path)
        cookie?.let { request.setCookies(Cookie(LoginGateFilter.COOKIE_NAME, it)) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return request to response
    }

    @Nested
    inner class 로그인_관문 {
        @Test
        fun `미로그인이면 로그인 뒤 경로를 막는다`() {
            val (_, response) = call("/api/leading-stocks/market-signal-events")

            response.status shouldBe 401
            response.contentAsString shouldContain "\"code\":\"UNAUTHORIZED\""
        }

        @Test
        fun `공개 화면은 로그인 없이도 열린다`() {
            call("/api/leading-stocks/candidates").second.status shouldBe 200
        }

        @Test
        fun `api 밖은 관문이 판단하지 않는다`() {
            call("/health").second.status shouldBe 200
        }

        @Test
        fun `Python이 발급한 쿠키로 로그인한 사용자를 알아본다`() {
            val (request, response) = call("/api/leading-stocks/market-signal-events", pythonCookie)

            response.status shouldBe 200
            request.getAttribute(LoginGateFilter.CURRENT_USER) shouldBe CurrentUser(7, "a@example.com")
        }

        @Test
        fun `서명이 틀린 쿠키는 미로그인으로 본다`() {
            call("/api/leading-stocks/market-signal-events", pythonCookie.dropLast(1) + "x").second.status shouldBe 401
        }
    }
}
