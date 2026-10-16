package judozoo.platform.toss

import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import judozoo.library.brokertoken.TokenSource
import java.time.Duration

/** 토스 엔드포인트 테스트용 — 공유 WireMock + 그 서버를 보는 [TossClient]. 429 뒤의 쉼은 [pauses]에 남기만 한다. */
class TossStub : AutoCloseable {
    val server = SharedWireMock.server.also { SharedWireMock.reset() }
    val pauses = mutableListOf<Duration>()
    var invalidated = 0

    val client = TossClient(
        TossProperties(baseUrl = server.baseUrl(), chartPermitsPerSecond = 100, indicatorChartPermitsPerSecond = 100),
        object : TokenSource {
            override fun get() = "tok"
            override fun invalidate() {
                invalidated++
            }
        },
        SimpleMeterRegistry(),
        pause = { pauses += it },
    )

    fun respond(path: String, json: String, status: Int = 200) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(json)))
    }

    /** 부를 때마다 차례로 답한다. 마지막 응답은 그 뒤로도 계속 준다. */
    fun respondInOrder(path: String, vararg responses: Pair<Int, String>) {
        responses.forEachIndexed { i, (status, json) ->
            var mapping: ScenarioMappingBuilder = get(urlPathEqualTo(path)).inScenario(path)
                .whenScenarioStateIs(if (i == 0) Scenario.STARTED else "$i")
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(json))
            if (i < responses.lastIndex) mapping = mapping.willSetStateTo("${i + 1}")
            server.stubFor(mapping)
        }
    }

    fun fail(path: String) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
    }

    fun calls(path: String) = server.countRequestsMatching(getRequestedFor(urlPathEqualTo(path)).build()).count

    fun requests(path: String) = server.findAll(getRequestedFor(urlPathEqualTo(path)))

    override fun close() = SharedWireMock.reset()
}
