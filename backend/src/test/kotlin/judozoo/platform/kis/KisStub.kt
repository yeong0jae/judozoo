package judozoo.platform.kis

import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import judozoo.library.brokertoken.TokenSource
import java.time.Duration

class KisStub : AutoCloseable {
    val server = SharedWireMock.server.also { SharedWireMock.reset() }
    val pauses = mutableListOf<Duration>()

    val client = KisClient(
        KisProperties(baseUrl = server.baseUrl(), appKey = "key", appSecret = "secret", queryPermitsPerSecond = 100),
        object : TokenSource {
            override fun get() = "tok"
            override fun invalidate() {}
        },
        SimpleMeterRegistry(),
        pause = { pauses += it },
    )

    fun respond(path: String, json: String, status: Int = 200) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(json)))
    }

    fun respondText(path: String, text: String, status: Int) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(text)))
    }

    fun respondInOrder(path: String, vararg responses: Pair<Int, String>) {
        responses.forEachIndexed { i, (status, json) ->
            val state = if (i == 0) Scenario.STARTED else "$i"
            var mapping: ScenarioMappingBuilder = get(urlPathEqualTo(path)).inScenario(path).whenScenarioStateIs(state)
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

    companion object {
        fun ok(json: String) = 200 to json
    }
}
