package judozoo.platform.kiwoom

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
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

class KiwoomTokenIssuerTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val now = Instant.parse("2026-10-15T05:00:00Z")
    private val issuer = KiwoomTokenIssuer(
        KiwoomProperties(baseUrl = server.baseUrl(), appKey = "key", appSecret = "secret"), Clock.fixed(now, ZoneOffset.UTC), SimpleMeterRegistry(),
    )

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    private fun respond(json: String) = server.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(okJson(json)))

    @Nested
    inner class 액세스_토큰 {
        @Test
        fun `앱키로 발급받아 23시간 쓴다`() {
            respond("""{"token":"tok-abc","return_code":0,"expires_dt":"20261016140000"}""")

            val issued = issuer.issue()

            (issued.token to issued.expiresAt) shouldBe ("tok-abc" to now + Duration.ofHours(23))
            server.verify(postRequestedFor(urlEqualTo("/oauth2/token")).withRequestBody(equalToJson("""{"grant_type":"client_credentials","appkey":"key","secretkey":"secret"}""")))
        }

        /** 응답이 token 대신 access_token으로 오는 경우가 있다. */
        @Test
        fun `access_token 필드로 와도 받는다`() {
            respond("""{"access_token":"tok-alt","return_code":0}""")

            issuer.issue().token shouldBe "tok-alt"
        }

        @Test
        fun `return_code가 0이 아니면 예외`() {
            respond("""{"return_code":3,"return_msg":"인증 실패"}""")

            shouldThrow<IllegalStateException> { issuer.issue() }.message!! shouldContain "인증 실패"
        }

        @Test
        fun `토큰이 비어있으면 예외`() {
            respond("""{"return_code":0}""")

            shouldThrow<IllegalStateException> { issuer.issue() }.message!! shouldContain "토큰 없음"
        }

        /** 키움은 거부할 때 `start.html`로 302를 준다 — 따라가면 거부가 200으로 보인다. */
        @Test
        fun `리다이렉트로 거부하면 따라가지 않고 예외`() {
            server.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withStatus(302).withHeader("location", "https://openapi.kiwoom.com/start.html")))

            shouldThrow<IllegalStateException> { issuer.issue() }.message!! shouldContain "302"
        }

        @Test
        fun `HTTP 오류는 예외로 올린다`() {
            server.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withStatus(500)))

            shouldThrow<IllegalStateException> { issuer.issue() }
        }

        @Test
        fun `연결이 끊기면 예외로 올린다`() {
            server.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

            shouldThrowAny { issuer.issue() }
        }
    }
}
