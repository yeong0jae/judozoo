package judozoo.platform.kis

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
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

class KisTokenIssuerTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val now = Instant.parse("2026-10-15T05:00:00Z")
    private val issuer = KisTokenIssuer(KisProperties(baseUrl = server.baseUrl(), appKey = "key", appSecret = "secret"), Clock.fixed(now, ZoneOffset.UTC), SimpleMeterRegistry())

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    @Nested
    inner class 액세스_토큰 {
        @Test
        fun `앱키로 발급받아 23시간 쓴다`() {
            server.stubFor(post(urlEqualTo("/oauth2/tokenP")).willReturn(okJson("""{"access_token":"tok","token_type":"Bearer","expires_in":86400}""")))

            val issued = issuer.issue()

            (issued.token to issued.expiresAt) shouldBe ("tok" to now + Duration.ofHours(23))
            server.verify(postRequestedFor(urlEqualTo("/oauth2/tokenP")).withRequestBody(equalToJson("""{"grant_type":"client_credentials","appkey":"key","appsecret":"secret"}""")))
        }

        /** KIS는 같은 앱키로 1분 안에 재발급하면 거부한다(EGW00133). 사유 코드가 본문에 온다. */
        @Test
        fun `거부되면 예외로 올린다`() {
            server.stubFor(post(urlEqualTo("/oauth2/tokenP")).willReturn(aResponse().withStatus(403).withBody("""{"error_code":"EGW00133"}""")))

            shouldThrow<IllegalStateException> { issuer.issue() }.message!! shouldContain "403"
        }

        @Test
        fun `토큰이 없으면 예외`() {
            server.stubFor(post(urlEqualTo("/oauth2/tokenP")).willReturn(okJson("{}")))

            shouldThrow<IllegalStateException> { issuer.issue() }.message!! shouldContain "access_token 없음"
        }
    }
}
