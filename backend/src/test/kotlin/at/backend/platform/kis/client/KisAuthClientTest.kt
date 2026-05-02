package at.backend.platform.kis.client

import at.backend.common.test.TestRestClientConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.web.client.RestClientException

class KisAuthClientTest : FunSpec({

    val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())

    beforeSpec { wireMock.start() }
    afterSpec { wireMock.stop() }
    beforeEach { wireMock.resetAll() }

    fun client() = KisAuthClient(
        appKey = "test-key",
        appSecret = "test-secret",
        restClient = TestRestClientConfig.restClient(wireMock),
    )

    context("정상 응답") {
        test("토큰 엔드포인트 응답 본문을 그대로 매핑") {
            wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/oauth2/tokenP"))
                    .willReturn(
                        WireMock.aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"access_token":"abc","access_token_token_expired":"2099-12-31 23:59:59"}""")
                    )
            )

            val response = client().issueToken()

            response.accessToken shouldBe "abc"
            response.accessTokenExpired shouldBe "2099-12-31 23:59:59"
        }
    }

    context("4xx 응답") {
        test("RestClient 예외가 그대로 전파") {
            wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/oauth2/tokenP"))
                    .willReturn(WireMock.aResponse().withStatus(401))
            )

            shouldThrow<RestClientException> { client().issueToken() }
        }
    }
})
