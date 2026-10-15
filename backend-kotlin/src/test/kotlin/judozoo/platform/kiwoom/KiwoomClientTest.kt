package judozoo.platform.kiwoom

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.library.BrokerTokenUnavailable
import judozoo.library.RateLimiter
import judozoo.library.brokertoken.TokenSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class KiwoomClientTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val registry = SimpleMeterRegistry()
    private val token = FakeToken()
    private lateinit var client: KiwoomClient

    @BeforeEach
    fun 서버() {
        server.start()
        client = KiwoomClient(
            KiwoomProperties(baseUrl = server.baseUrl(), queryPermitsPerSecond = 100, peakQueryPermitsPerSecond = 100),
            token,
            Clock.fixed(Instant.parse("2026-10-15T05:00:00Z"), ZoneOffset.UTC),
            registry,
        )
    }

    @AfterEach
    fun 정리() = server.stop()

    private fun query(apiId: String = "ka10032") = client.query("/api/dostk/rkinfo", apiId, mapOf("mrkt_tp" to "000"))

    @Nested
    inner class 조회 {
        @Test
        fun `토큰과 TR을 헤더에 실어 보내고 본문을 돌려준다`() {
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo"))
                    .withHeader("authorization", equalTo("Bearer tok-1"))
                    .withHeader("api-id", equalTo("ka10032"))
                    .withRequestBody(equalToJson("""{"mrkt_tp":"000"}"""))
                    .willReturn(okJson("""{"return_code":0,"trde_prica_upper":[{"stk_cd":"005930"}]}""")),
            )

            query().body["trde_prica_upper"][0]["stk_cd"].asString() shouldBe "005930"
        }

        @Test
        fun `연속 조회 헤더를 읽는다`() {
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo")).willReturn(
                    okJson("""{"return_code":0}""").withHeader("cont-yn", "Y").withHeader("next-key", "abc"),
                ),
            )

            val response = query()

            response.hasNext shouldBe true
            response.nextKey shouldBe "abc"
        }

        @Test
        fun `return_code가 0이 아니면 예외`() {
            server.stubFor(post(urlEqualTo("/api/dostk/rkinfo")).willReturn(okJson("""{"return_code":3,"return_msg":"조회 실패"}""")))

            shouldThrow<KiwoomApiException> { query() }.code shouldBe 3
        }

        @Test
        fun `HTTP 오류는 그대로 올린다`() {
            server.stubFor(post(urlEqualTo("/api/dostk/rkinfo")).willReturn(aResponse().withStatus(500)))

            shouldThrow<HttpServerErrorException> { query() }
        }

        @Test
        fun `연결이 끊기면 그대로 올린다`() {
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo")).willReturn(
                    aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER),
                ),
            )

            shouldThrow<ResourceAccessException> { query() }
        }

        @Test
        fun `호출 결과를 TR별로 센다`() {
            server.stubFor(post(urlEqualTo("/api/dostk/rkinfo")).willReturn(okJson("""{"return_code":0}""")))

            query()

            registry.counter("http_client_requests", "vendor", "kiwoom", "endpoint", "ka10032", "status", "200").count() shouldBe 1.0
        }
    }

    @Nested
    inner class 토큰_무효 {
        private fun stubInvalidThenOk() {
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo")).inScenario("8005").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(okJson("""{"return_code":8005,"return_msg":"Token이 유효하지 않습니다"}"""))
                    .willSetStateTo("재발급됨"),
            )
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo")).inScenario("8005").whenScenarioStateIs("재발급됨")
                    .willReturn(okJson("""{"return_code":0,"ok":true}""")),
            )
        }

        @Test
        fun `토큰을 버리고 한 번 다시 부른다`() {
            stubInvalidThenOk()

            query().body["ok"].asBoolean() shouldBe true
            token.invalidated shouldBe 1
            server.verify(2, postRequestedFor(urlEqualTo("/api/dostk/rkinfo")))
        }

        @Test
        fun `코드 없이 메시지로만 와도 알아본다`() {
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo"))
                    .willReturn(okJson("""{"return_code":1,"return_msg":"[8005] 인증 오류"}""")),
            )

            shouldThrow<KiwoomApiException> { query() }.isInvalidToken shouldBe true
            token.invalidated shouldBe 1
        }

        @Test
        fun `두 번째도 무효면 더 부르지 않는다`() {
            server.stubFor(
                post(urlEqualTo("/api/dostk/rkinfo")).willReturn(okJson("""{"return_code":8005,"return_msg":"x"}""")),
            )

            shouldThrow<KiwoomApiException> { query() }
            server.verify(2, postRequestedFor(urlEqualTo("/api/dostk/rkinfo")))
        }

        @Test
        fun `새 토큰이 아직 없으면 기다리라고 답한다`() {
            stubInvalidThenOk()
            token.availableAfterInvalidate = false

            shouldThrow<BrokerTokenUnavailable> { query() }
            server.verify(1, postRequestedFor(urlEqualTo("/api/dostk/rkinfo")))
        }
    }

    @Nested
    inner class 조회_리미터 {
        /** ka*는 조회 한도를 타고, 주문·계좌(kt*)는 별도 한도라 통과시킨다. */
        @Test
        fun `조회 TR만 허가를 받는다`() {
            val limiter = RateLimiter("테스트", 1, registry, { Duration.ofHours(1) }, Duration.ofMillis(1))
            val interceptor = KiwoomClient.queryRateLimit(limiter)
            fun send(apiId: String?) {
                val request = MockClientHttpRequest(HttpMethod.POST, URI("http://x"))
                apiId?.let { request.headers.set(KiwoomClient.API_ID, it) }
                interceptor.intercept(request, ByteArray(0)) { _, _ -> MockClientHttpResponse() }
            }

            send("kt10000")
            send(null)
            send("ka10032") // 버킷의 유일한 허가를 쓴다

            shouldThrow<judozoo.library.RateLimitTimeout> { send("ka10059") }
        }
    }

    @Nested
    inner class 피크타임_한도 {
        /** 09:00~10:00에는 키움 조회 한도가 초당 5건에서 3건으로 내려간다. */
        @ParameterizedTest
        @CsvSource("08:59:59, 5", "09:00:00, 3", "09:59:59, 3", "10:00:00, 5")
        fun `시간대에 맞는 간격으로 허가를 낸다`(at: String, perSecond: Long) {
            val properties = KiwoomProperties(queryPermitsPerSecond = 5, peakQueryPermitsPerSecond = 3)

            KiwoomClient.queryPeriod(LocalTime.parse(at), properties) shouldBe Duration.ofNanos(1_000_000_000L / perSecond)
        }
    }

    private class FakeToken : TokenSource {
        var invalidated = 0
        var availableAfterInvalidate = true

        override fun get(): String {
            if (invalidated > 0 && !availableAfterInvalidate) throw BrokerTokenUnavailable("KIWOOM 토큰 없음")
            return if (invalidated == 0) "tok-1" else "tok-2"
        }

        override fun invalidate() {
            invalidated++
        }
    }
}
