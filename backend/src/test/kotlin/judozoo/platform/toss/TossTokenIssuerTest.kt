package judozoo.platform.toss

import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TossTokenIssuerTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val now = Instant.parse("2026-10-15T05:00:00Z")
    private val issuer = TossTokenIssuer(
        TossProperties(baseUrl = server.baseUrl(), clientId = " id\n", clientSecret = "secret "), Clock.fixed(now, ZoneOffset.UTC), SimpleMeterRegistry(),
    )

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    private fun respond(json: String) = server.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(okJson(json)))

    @Nested
    inner class 액세스_토큰 {
        /** 시크릿 주입 시 끼어든 개행·공백이 남아 있으면 401 invalid_client로 거부된다. */
        @Test
        fun `만료 5분 전까지 쓰고 시크릿의 공백은 걷어 보낸다`() {
            respond("""{"access_token":"tok","expires_in":86400}""")

            val issued = issuer.issue()

            (issued.token to issued.expiresAt) shouldBe ("tok" to now + Duration.ofSeconds(86_400 - 300))
            server.verify(postRequestedFor(urlEqualTo("/oauth2/token")).withRequestBody(containing("client_id=id&client_secret=secret")))
        }

        /** expires_in이 만료 마진보다 짧으면 음수 수명이 된다. */
        @Test
        fun `수명이 짧아도 최소치는 보장한다`() {
            respond("""{"access_token":"tok","expires_in":60}""")

            issuer.issue().expiresAt shouldBe now + Duration.ofSeconds(60)
        }

        @Test
        fun `토큰이 비면 예외`() {
            respond("{}")

            shouldThrow<IllegalStateException> { issuer.issue() }.message!! shouldContain "비어있음"
        }
    }
}
