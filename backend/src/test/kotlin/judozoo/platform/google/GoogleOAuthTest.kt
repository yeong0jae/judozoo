package judozoo.platform.google

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class GoogleOAuthTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private val google = GoogleOAuth(
        GoogleProperties(clientId = "app-id", clientSecret = "app-secret", redirectUri = "https://judozoo.com/api/auth/callback", tokenUrl = "${server.baseUrl()}/token"),
        Clock.fixed(now, ZoneOffset.UTC), SimpleMeterRegistry(),
    )

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    private fun idToken(claims: String): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return "${b64("""{"alg":"RS256"}""")}.${b64(claims)}.sig"
    }

    private fun answer(claims: String) =
        server.stubFor(post(urlEqualTo("/token")).willReturn(okJson("""{"access_token":"at","id_token":"${idToken(claims)}"}""")))

    private val good = """{"iss":"https://accounts.google.com","aud":"app-id","exp":${now.epochSecond + 3600},"nonce":"n-1","sub":"g-123","email":"a@example.com"}"""

    @Nested
    inner class 로그인_시작 {
        @Test
        fun `구글 로그인 화면으로 state와 nonce를 실어 보낸다`() {
            val url = google.authorizeUrl("s-1", "n-1")

            url shouldContain "https://accounts.google.com/o/oauth2/v2/auth?"
            url shouldContain "client_id=app-id"
            url shouldContain "scope=openid%20email"
            url shouldContain "state=s-1"
            url shouldContain "nonce=n-1"
            url shouldContain "redirect_uri=https://judozoo.com/api/auth/callback"
        }
    }

    @Nested
    inner class 콜백 {
        @Test
        fun `code를 바꿔 ID 토큰의 계정을 꺼낸다`() {
            answer(good)

            google.exchange("code-1", "n-1") shouldBe GoogleOAuth.Account("g-123", "a@example.com")
            server.verify(postRequestedFor(urlEqualTo("/token")).withRequestBody(containing("grant_type=authorization_code&code=code-1&client_id=app-id&client_secret=app-secret")))
        }

        @Test
        fun `nonce가 다르면 받지 않는다`() {
            answer(good)

            google.exchange("code-1", "n-other").shouldBeNull()
        }

        @Test
        fun `다른 앱의 토큰이면 받지 않는다`() {
            answer(good.replace("\"aud\":\"app-id\"", "\"aud\":\"other\""))

            google.exchange("code-1", "n-1").shouldBeNull()
        }

        @Test
        fun `구글이 발급하지 않았으면 받지 않는다`() {
            answer(good.replace("https://accounts.google.com", "https://evil.example"))

            google.exchange("code-1", "n-1").shouldBeNull()
        }

        @Test
        fun `만료된 토큰은 받지 않는다`() {
            answer(good.replace("${now.epochSecond + 3600}", "${now.epochSecond}"))

            google.exchange("code-1", "n-1").shouldBeNull()
        }

        @Test
        fun `이메일이 없으면 받지 않는다`() {
            answer(good.replace(",\"email\":\"a@example.com\"", ""))

            google.exchange("code-1", "n-1").shouldBeNull()
        }

        @Test
        fun `구글이 거절하면 예외로 올린다`() {
            server.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(400).withBody("""{"error":"invalid_grant"}""")))

            shouldThrowAny { google.exchange("code-1", "n-1") }
        }
    }
}
