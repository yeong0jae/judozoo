package judozoo.platform.vertex

import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import judozoo.platform.vertex.VertexException.Kind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class VertexClientTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val waits = mutableListOf<Duration>()
    private var tokens: (Boolean) -> String = { "test-token" }
    private var jitter = 1.0

    private val vertex = VertexClient(
        VertexProperties(baseUrl = server.baseUrl(), readTimeout = Duration.ofMillis(300)),
        { refresh -> tokens(refresh) },
        SimpleMeterRegistry(),
        wait = { waits += it },
        jitter = { jitter },
    )

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    private fun answer(text: String, finish: String = "STOP", grounding: String? = null) =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":${quote(text)}}]},"finishReason":"$finish"
        ${grounding?.let { ",\"groundingMetadata\":$it" }.orEmpty()}}],
        "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":240,"totalTokenCount":360}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun respond(status: Int, body: String) {
        server.stubFor(post(urlEqualTo(URL)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
    }

    private fun respondInOrder(vararg responses: Pair<Int, String>) {
        responses.forEachIndexed { i, (status, body) ->
            var mapping: ScenarioMappingBuilder = post(urlEqualTo(URL)).inScenario("vertex")
                .whenScenarioStateIs(if (i == 0) Scenario.STARTED else "$i")
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body))
            if (i < responses.lastIndex) mapping = mapping.willSetStateTo("${i + 1}")
            server.stubFor(mapping)
        }
    }

    private fun calls() = server.countRequestsMatching(postRequestedFor(urlEqualTo(URL)).build()).count

    @Nested
    inner class 검색_켠_호출 {
        @Test
        fun `설명 문장과 출처 검색어 수를 읽는다`() {
            respond(
                200,
                answer(
                    "LG이노텍은 美 라이다 업체 지분가치 상승으로 올랐다.",
                    grounding = """{"webSearchQueries":["LG이노텍 2026년 9월 28일","LG이노텍 급등 이유"],"groundingChunks":[
                    {"web":{"domain":"fnnews.com","title":"fnnews.com","uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/A"}},
                    {"web":{"domain":"daum.net","title":"daum.net","uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/B"}}]}""",
                ),
            )

            val g = vertex.ground("왜 올랐나")

            g.text shouldStartWith "LG이노텍은"
            g.sources.map { it.domain to it.uri.last() } shouldBe listOf("fnnews.com" to 'A', "daum.net" to 'B')
            g.usage.searchQueries shouldBe 2
            (g.usage.inputTokens to g.usage.outputTokens) shouldBe (120L to 240L)
        }

        @Test
        fun `출처가 없으면 빈 목록이다`() {
            respond(200, answer("기사로 설명되지 않는다."))

            val g = vertex.ground("왜 올랐나")

            g.sources.shouldBeEmpty()
            g.usage.searchQueries shouldBe 0
        }

        @Test
        fun `사고 조각은 답 문장에 섞지 않는다`() {
            respond(200, """{"candidates":[{"content":{"parts":[{"text":"생각 중…","thought":true},{"text":"답이다."}]},"finishReason":"STOP"}]}""")

            vertex.ground("왜").text shouldBe "답이다."
        }
    }

    @Nested
    inner class JSON_호출 {
        @Test
        fun `JSON 객체를 돌려준다`() {
            respond(200, answer("""{"explained": true, "evidence": [1]}"""))

            val (data, usage) = vertex.structure("정리해", mapOf("type" to "OBJECT"))

            data["explained"].asBoolean() shouldBe true
            data["evidence"][0].asInt() shouldBe 1
            usage.searchQueries shouldBe 0
        }

        @Test
        fun `JSON이 깨지면 실패로 본다`() {
            respond(200, answer("""{"explained": tru"""))

            val e = shouldThrow<VertexException> { vertex.structure("정리해", mapOf("type" to "OBJECT")) }
            e.kind shouldBe Kind.BAD_ANSWER
            e.message shouldContain "JSON"
        }

        @Test
        fun `객체가 아닌 JSON도 실패로 본다`() {
            respond(200, answer("[1, 2]"))

            shouldThrow<VertexException> { vertex.structure("정리해", mapOf("type" to "OBJECT")) }.message shouldContain "객체"
        }
    }

    @Nested
    inner class 실패 {
        @Test
        fun `한도 초과는 잠깐 기다렸다 두 번 더 묻는다`() {
            respondInOrder(429 to """{"error":{"message":"quota"}}""", 503 to "busy", 200 to answer("이제 된다"))

            vertex.ground("왜").text shouldBe "이제 된다"
            calls() shouldBe 3
        }

        @Test
        fun `두 번 더 물어도 한도 초과면 일시 장애로 올린다`() {
            respond(429, """{"error":{"message":"quota"}}""")

            val e = shouldThrow<VertexException> { vertex.ground("왜") }
            e.kind shouldBe Kind.UNAVAILABLE
            e.message shouldContain "429"
            calls() shouldBe 3
        }

        @Test
        fun `다시 묻기 전 기다림에 지터를 섞는다`() {
            jitter = 0.5
            respond(500, "boom")

            shouldThrow<VertexException> { vertex.ground("왜") }

            waits shouldBe listOf(Duration.ofSeconds(1), Duration.ofSeconds(2))
        }

        @Test
        fun `시간 초과는 다시 묻지 않고 바로 올린다`() {
            server.stubFor(post(urlEqualTo(URL)).willReturn(okJson(answer("늦은 답")).withFixedDelay(1_000)))

            shouldThrow<VertexException> { vertex.ground("왜") }.kind shouldBe Kind.TIMEOUT
            calls() shouldBe 1
        }

        @Test
        fun `연결이 끊기면 일시 장애다`() {
            server.stubFor(post(urlEqualTo(URL)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

            shouldThrow<VertexException> { vertex.ground("왜") }.kind shouldBe Kind.UNAVAILABLE
        }

        @ParameterizedTest
        @ValueSource(ints = [400, 403, 404])
        fun `설정이 틀린 오류는 다시 묻지 않는다`(status: Int) {
            respond(status, "no")

            shouldThrow<VertexException> { vertex.ground("왜") }.kind shouldBe Kind.CLIENT_ERROR
            calls() shouldBe 1
        }

        @Test
        fun `인증이 만료되면 토큰을 새로 받아 한 번 더 묻는다`() {
            tokens = { refresh -> if (refresh) "new" else "old" }
            server.stubFor(post(urlEqualTo(URL)).withHeader("Authorization", equalTo("Bearer old")).willReturn(aResponse().withStatus(401).withBody("expired")))
            server.stubFor(post(urlEqualTo(URL)).withHeader("Authorization", equalTo("Bearer new")).willReturn(okJson(answer("답"))))

            vertex.ground("왜").text shouldBe "답"
        }

        @Test
        fun `새 토큰으로도 인증이 안 되면 설정 오류다`() {
            respond(401, "expired")

            shouldThrow<VertexException> { vertex.ground("왜") }.kind shouldBe Kind.CLIENT_ERROR
        }

        @Test
        fun `어느 호출에서 실패했는지 문구에 남긴다`() {
            server.stubFor(post(urlEqualTo(URL)).willReturn(okJson(answer("늦은 답")).withFixedDelay(1_000)))

            shouldThrow<VertexException> { vertex.structure("정리해", mapOf("type" to "OBJECT")) }.message shouldStartWith "structure "
        }

        @Test
        fun `안전 차단으로 끝난 답은 쓰지 않는다`() {
            respond(200, answer("", finish = "SAFETY"))

            val e = shouldThrow<VertexException> { vertex.ground("왜") }
            e.kind shouldBe Kind.BAD_ANSWER
            e.message shouldContain "SAFETY"
        }

        @Test
        fun `후보가 없으면 실패다`() {
            respond(200, """{"promptFeedback":{"blockReason":"OTHER"}}""")

            shouldThrow<VertexException> { vertex.ground("왜") }.kind shouldBe Kind.BAD_ANSWER
        }
    }

    companion object {
        private const val URL = "/v1/projects/trading-496508/locations/global/publishers/google/models/gemini-3.8-flash:generateContent"
    }
}
